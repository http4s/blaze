package org.http4s.blaze.channel.nio1

import scala.util.Try
import java.nio.ByteBuffer
import java.nio.channels.{CancelledKeyException, SelectionKey, SelectableChannel}
import org.http4s.blaze.pipeline.HeadStage
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.util.BufferTools

/**
 * @author Bryce Anderson
 *         Created on 1/21/14
 */

private[nio1] object NIO1HeadStage {
  sealed trait WriteResult
  case object Complete extends WriteResult
  case object Incomplete extends WriteResult
  case object ChannelClosed extends WriteResult
  case class WriteError(t: Exception) extends WriteResult
}

private[nio1] abstract class NIO1HeadStage extends HeadStage[ByteBuffer] {
  import NIO1HeadStage._

  protected def ch: SelectableChannel

  protected def key: SelectionKey

  protected def loop: SelectorLoop

  def name: String = "NIO1 ByteBuffer Head Stage"

  ///  channel reading bits //////////////////////////////////////////////

  private val readPromise = new AtomicReference[Promise[ByteBuffer]]

  def readReady(scratch: ByteBuffer) {
    val r = performRead(scratch)

    // if we successfully read some data, unset the interest and complete the promise
    if (r != null) {
      unsetOp(SelectionKey.OP_READ)
      val p = readPromise.getAndSet(null)
      p.complete(r)
    }
  }

  def readRequest(size: Int): Future[ByteBuffer] = {
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
  def writeReady(scratch: ByteBuffer): Unit = performWrite(scratch, writeData) match {
    case Complete =>
      val p = writePromise.get
      writeData = null
      writePromise.set(null)
      unsetOp(SelectionKey.OP_WRITE)
      p.success(())

    case Incomplete => /* Need to wait for another go around to try and send more data */

    case ChannelClosed => writeFailAndClose(EOF)

    case WriteError(NonFatal(t)) =>
      logger.warn("Error performing write", t)
      writeFailAndClose(t)

    case WriteError(t) =>
      logger.error("Serious error while performing write. Shutting down connector", t)
      writeFailAndClose(t)
  }

  def writeRequest(data: ByteBuffer): Future[Unit] = writeRequest(data::Nil)

  override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] = {
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

  // Cleanup any read or write requests with an exception
  def closeWithError(t: Throwable): Unit = {
    val r = readPromise.getAndSet(null)
    if (r != null) r.tryFailure(t)
    writeFailAndClose(t)
  }

  // Cancel the promises and close the channel
  private def writeFailAndClose(t: Throwable) {
    val w = writePromise.getAndSet(null)
    writeData = null
    if (w != null) w.tryFailure(t)

    try close()
    catch { case NonFatal(t) => logger.warn("Caught exception while closing channel", t) }
  }

  override protected def stageShutdown(): Unit = {
    super.stageShutdown()
    closeWithError(EOF)
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

  /** Don't close until the next cycle */
  def close(): Unit = loop.enqueTask(new Runnable {
    def run() = {
      if (key.isValid) key.interestOps(0)
      key.attach(null)
      ch.close()
    }
  })

  private val _readTask = new Runnable {
    def run() { _setOp(SelectionKey.OP_READ) }
  }

  private val _writeTask = new Runnable {
    def run() { _setOp(SelectionKey.OP_WRITE) }
  }

  def unsetOp(op: Int) {
    if (Thread.currentThread() == loop) _unsetOp(op)  // Already in SelectorLoop
    else loop.enqueTask(new Runnable { def run() = _unsetOp(op) })
  }

  final private def _unsetOp(op: Int) {
    try {
      val ops = key.interestOps()
      if ((ops & op) != 0) {
        key.interestOps(ops & ~op)
      }
    } catch { case _: CancelledKeyException => closeWithError(EOF) }

  }

  final private def _setOp(op: Int) {
    try {
      val ops = key.interestOps()
      if ((ops & op) == 0) {
        key.interestOps(ops | op)
      }
    } catch { case _: CancelledKeyException => closeWithError(EOF) }

  }
}