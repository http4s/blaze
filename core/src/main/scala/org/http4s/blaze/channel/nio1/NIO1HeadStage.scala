package org.http4s.blaze.channel.nio1

import org.http4s.blaze.channel.ChannelHead

import scala.util.{Failure, Success, Try}
import java.nio.ByteBuffer
import java.nio.channels.{CancelledKeyException, SelectionKey, SelectableChannel}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.util.BufferTools


private[nio1] object NIO1HeadStage {
  sealed trait WriteResult
  case object Complete extends WriteResult
  case object Incomplete extends WriteResult
  case object ChannelClosed extends WriteResult
  case class WriteError(t: Exception) extends WriteResult
}

private[nio1] abstract class NIO1HeadStage(ch: SelectableChannel,
                                           loop: SelectorLoop,
                                           key: SelectionKey) extends ChannelHead
{
  import NIO1HeadStage._

  override def name: String = "NIO1 ByteBuffer Head Stage"

  ///  channel reading bits //////////////////////////////////////////////

  private val readPromise = new AtomicReference[Promise[ByteBuffer]]

  // Called by the selector loop when this channel has data to read
  final def readReady(scratch: ByteBuffer) {
    val r = performRead(scratch)
    unsetOp(SelectionKey.OP_READ)
    val p = readPromise.getAndSet(null)

    // if we successfully read some data, unset the interest and
    // complete the promise, otherwise fail appropriately
    r match {
      case Success(_)   => p.complete(r)
      case Failure(EOF) => closeChannel()
      case Failure(e)   => p.tryFailure(checkError(e))
    }
  }

  final override def readRequest(size: Int): Future[ByteBuffer] = {
    logger.trace("NIOHeadStage received a read request")
    val p = Promise[ByteBuffer]

    if (readPromise.compareAndSet(null, p)) {
      if (Thread.currentThread() == loop) _readTask.run()  // Already in SelectorLoop
      else loop.enqueTask(_readTask)
      p.future
    }
    else Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending read request"))
  }

  /// channel write bits /////////////////////////////////////////////////

  @volatile private var writeData: Array[ByteBuffer] = null
  private val writePromise = new AtomicReference[Promise[Unit]](null)

  // Will always be called from the SelectorLoop thread
  final def writeReady(scratch: ByteBuffer): Unit = performWrite(scratch, writeData) match {
    case Complete =>
      val p = writePromise.get
      writeData = null
      writePromise.set(null)
      unsetOp(SelectionKey.OP_WRITE)
      p.success(())

    case Incomplete => /* Need to wait for another go around to try and send more data */

    case ChannelClosed =>
      closeChannel()

    case WriteError(t) =>
      logger.error(t)("Error while performing write. Shutting down connector")
      closeWithError(t)
  }

  final override def writeRequest(data: ByteBuffer): Future[Unit] = writeRequest(data::Nil)

  final override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] = {
    val p = Promise[Unit]
    if (writePromise.compareAndSet(null, p)) {
      val writes = data.toArray
      if (!BufferTools.checkEmpty(writes)) {  // Non-empty buffer
        writeData = writes
        if (Thread.currentThread() == loop) _writeTask.run()  // Already in SelectorLoop
        else loop.enqueTask(_writeTask)
        p.future
      } else {                                // Empty buffer, just return success
        writePromise.set(null)
        writeData = null
        Future.successful(())
      }
    } else {
      Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending write request"))
    }
  }

  ///////////////////////////////// Shutdown methods ///////////////////////////////////

  /** Shutdown the channel with an EOF for any pending OPs */
  override protected def stageShutdown(): Unit = {
    closeWithError(EOF)
    super.stageShutdown()
  }

  ///////////////////////////////// Channel Ops ////////////////////////////////////////

  /** Performs the read operation
    * @param scratch a ByteBuffer which is owned by the SelectorLoop to use as
    *                scratch space until this method returns
    * @return a Try with either a successful ByteBuffer, an error, or null if this operation is not complete
    */
  def performRead(scratch: ByteBuffer): Try[ByteBuffer]

  /** Perform the write operation for this channel
    * @param buffers buffers to be written to the channel
    * @return a Try that is either a Success(Any), a Failure with an appropriate error,
    *         or null if this operation is not complete
    */
  def performWrite(scratch: ByteBuffer, buffers: Array[ByteBuffer]): WriteResult

  /** Don't actually close until the next cycle */
  final override protected def closeChannel(): Unit = closeWithError(EOF)

  // Cleanup any read or write requests with an exception
  final override def closeWithError(t: Throwable): Unit = {
    val r = readPromise.getAndSet(null)
    if (r != null) r.tryFailure(t)

    val w = writePromise.getAndSet(null)
    writeData = null
    if (w != null) w.tryFailure(t)

    try loop.enqueTask(new Runnable {
      def run() = {
        if (key.isValid) key.interestOps(0)
        key.attach(null)
        ch.close()
      }
    })
    catch { case NonFatal(t) => logger.warn(t)("Caught exception while closing channel") }
  }

  // These are just here to be reused
  private val _readTask = new Runnable {
    def run() { _setOp(SelectionKey.OP_READ) }
  }

  // These are just here to be reused
  private val _writeTask = new Runnable {
    def run() { _setOp(SelectionKey.OP_WRITE) }
  }

  /** Sets a channel interest */
  private def unsetOp(op: Int) {
    if (Thread.currentThread() == loop) _unsetOp(op)  // Already in SelectorLoop
    else loop.enqueTask(new Runnable { def run() = _unsetOp(op) })
  }

  private def _unsetOp(op: Int) {
    try {
      val ops = key.interestOps()
      if ((ops & op) != 0) {
        key.interestOps(ops & ~op)
      }
    } catch { case _: CancelledKeyException => closeChannel() }
  }

  private def _setOp(op: Int) {
    try {
      val ops = key.interestOps()
      if ((ops & op) == 0) {
        key.interestOps(ops | op)
      }
    } catch { case _: CancelledKeyException => closeChannel() }
  }
}