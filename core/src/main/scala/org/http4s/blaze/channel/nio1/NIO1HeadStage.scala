package org.http4s.blaze.channel.nio1

import org.http4s.blaze.channel.ChannelHead

import scala.util.{Failure, Success, Try}
import java.nio.ByteBuffer
import java.nio.channels.{CancelledKeyException, SelectableChannel, SelectionKey}
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import org.http4s.blaze.pipeline.Command.{Disconnected, EOF}
import org.http4s.blaze.util.BufferTools

private[nio1] object NIO1HeadStage {

  private val cachedSuccess = Success(())

  sealed trait WriteResult
  case object Complete extends WriteResult
  case object Incomplete extends WriteResult
  case class WriteError(t: Exception) extends WriteResult // EOF signals normal termination

}

private[nio1] abstract class NIO1HeadStage(
    ch: SelectableChannel,
    loop: SelectorLoop,
    key: SelectionKey
) extends ChannelHead with Selectable {
  import NIO1HeadStage._

  override def name: String = "NIO1 ByteBuffer Head Stage"

  // State of the HeadStage. These should only be accessed from the SelectorLoop thread
  // will only be written to inside of 'closeWithError'
  private var closedReason: Throwable = null

  private var readPromise: Promise[ByteBuffer] = null

  private var writeData: Array[ByteBuffer] = null
  private var writePromise: Promise[Unit] = null

  final def opsReady(scratch: ByteBuffer): Unit = {
    val readyOps = key.readyOps
    if ((readyOps & SelectionKey.OP_READ) != 0) readReady(scratch)
    if ((readyOps & SelectionKey.OP_WRITE) != 0) writeReady(scratch)
  }

  // Called by the selector loop when this channel has data to read
  private[this] def readReady(scratch: ByteBuffer): Unit = {
    // assert(Thread.currentThread() == loop,
    //       s"Expected to be called only by SelectorLoop thread, was called by ${Thread.currentThread.getName}")

    val r = performRead(scratch)
    unsetOp(SelectionKey.OP_READ)

    // if we successfully read some data, unset the interest and
    // complete the promise, otherwise fail appropriately
    r match {
      case Success(_) =>
        val p = readPromise
        readPromise = null
        if (p != null) {
          p.tryComplete(r)
          ()
        } else {
          /* NOOP: was handled during an exception event */
          ()
        }

      case Failure(e) =>
        val ee = checkError(e)
        sendInboundCommand(Disconnected)
        closeWithError(ee) // will complete the promise with the error
        ()
    }
  }

  private[this] def writeReady(scratch: ByteBuffer): Unit = {
    //assert(Thread.currentThread() == loop,
    //       s"Expected to be called only by SelectorLoop thread, was called by ${Thread.currentThread.getName}")

    val buffers = writeData // get a local reference so we don't hit the volatile a lot
    performWrite(scratch, buffers) match {
      case Complete =>
        writeData = null
        unsetOp(SelectionKey.OP_WRITE)
        val p = writePromise
        writePromise = null
        if (p != null) {
          p.tryComplete(cachedSuccess)
          ()
        } else {
          /* NOOP: channel must have been closed in some manner */
          ()
        }

      case Incomplete =>
        /* Need to wait for another go around to try and send more data */
        BufferTools.dropEmpty(buffers)
        ()

      case WriteError(t) =>
        unsetOp(SelectionKey.OP_WRITE)
        sendInboundCommand(Disconnected)
        closeWithError(t)
    }
  }

  ///  channel reading bits //////////////////////////////////////////////

  final override def readRequest(size: Int): Future[ByteBuffer] = {
    logger.trace(s"NIOHeadStage received a read request of size $size")
    val p = Promise[ByteBuffer]

    loop.executeTask(new Runnable {
      override def run(): Unit =
        if (closedReason != null) {
          p.tryFailure(closedReason)
          ()
        } else if (readPromise == null) {
          readPromise = p
          setOp(SelectionKey.OP_READ)
          ()
        } else {
          p.tryFailure(new IllegalStateException("Cannot have more than one pending read request"))
          ()
        }
    })
    p.future
  }

  /// channel write bits /////////////////////////////////////////////////

  final override def writeRequest(data: ByteBuffer): Future[Unit] =
    writeRequest(data :: Nil)

  final override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] = {
    logger.trace(s"NIO1HeadStage Write Request: $data")
    val p = Promise[Unit]
    loop.executeTask(new Runnable {
      override def run(): Unit =
        if (closedReason != null) {
          p.tryFailure(closedReason)
          ()
        } else if (writePromise == null) {
          val writes = data.toArray
          if (!BufferTools.checkEmpty(writes)) { // Non-empty buffer
            writePromise = p
            writeData = writes
            setOp(SelectionKey.OP_WRITE)
            p.future
            ()
          } else {
            // Empty buffer, just return success
            p.tryComplete(cachedSuccess)
            ()
          }
        } else {
          val t = new IllegalStateException("Cannot have more than one pending write request")
          logger.error(t)(s"Multiple pending write requests")
          p.tryFailure(t)
          ()
        }
    })

    p.future
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
  protected def performRead(scratch: ByteBuffer): Try[ByteBuffer]

  /** Perform the write operation for this channel
    * @param buffers buffers to be written to the channel
    * @return a WriteResult that is one of Complete, Incomplete or WriteError(e: Exception)
    */
  protected def performWrite(scratch: ByteBuffer, buffers: Array[ByteBuffer]): WriteResult


  final override def close(): Unit = closeWithError(EOF)

  // Cleanup any read or write requests with the Throwable
  final override def closeWithError(t: Throwable): Unit = {

    // intended to be called from within the SelectorLoop but if
    // it's closed it will be performed in the current thread
    def doClose(t: Throwable): Unit = {

      // this is the only place that writes to the variable
      if (closedReason == null) {
        closedReason = t
      } else if (closedReason != EOF && closedReason != t) {
        closedReason.addSuppressed(t)
      }

      if (readPromise != null) {
        readPromise.tryFailure(t)
        readPromise = null
      }

      if (writePromise != null) {
        writePromise.tryFailure(t)
        writePromise = null
      }

      writeData = null
      sendInboundCommand(Disconnected)
    }

    try loop.executeTask(new Runnable {
      def run() = {
        logger.trace(s"closeWithError($t); readPromise: $readPromise, writePromise: $writePromise")
        if (t != EOF) logger.error(t)("Abnormal NIO1HeadStage termination")

        if (key.isValid) key.interestOps(0)
        key.attach(null)
        ch.close()

        doClose(t)
      }
    })
    catch {
      case NonFatal(t2) =>
        t2.addSuppressed(t)
        logger.error(t2)(
          "Caught exception while closing channel: trying to close from " +
            s"${Thread.currentThread.getName}")
        doClose(t2)
    }
  }

  /** Unsets a channel interest
    *  only to be called by the SelectorLoop thread
   **/
  private def unsetOp(op: Int): Unit =
    // assert(Thread.currentThread() == loop,
    //       s"Expected to be called only by SelectorLoop thread, was called by ${Thread.currentThread.getName}")
    try {
      val ops = key.interestOps()
      if ((ops & op) != 0) {
        key.interestOps(ops & ~op)
        ()
      }
    } catch {
      case _: CancelledKeyException =>
        closeWithError(EOF)
    }

  // only to be called by the SelectorLoop thread
  private def setOp(op: Int): Unit =
    // assert(Thread.currentThread() == loop,
    //       s"Expected to be called only by SelectorLoop thread, was called by ${Thread.currentThread.getName}")
    try {
      val ops = key.interestOps()
      if ((ops & op) == 0) {
        key.interestOps(ops | op)
        ()
      }
    } catch {
      case _: CancelledKeyException =>
        closeWithError(EOF)
    }
}
