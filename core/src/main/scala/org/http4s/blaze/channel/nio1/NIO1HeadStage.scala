package org.http4s.blaze.channel.nio1

import org.http4s.blaze.channel.ChannelHead

import scala.util.{Failure, Success, Try}
import java.nio.ByteBuffer
import java.nio.channels.{CancelledKeyException, SelectionKey, SelectableChannel}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import org.http4s.blaze.pipeline.Command.{Disconnected, EOF}
import org.http4s.blaze.util.BufferTools


private[nio1] object NIO1HeadStage {
  
  private class PipeClosedPromise[T](t: Throwable) extends Promise[T] {
    override val future: Future[T] = Future.failed(t)
    override def tryComplete(result: Try[T]): Boolean = false
    override def isCompleted: Boolean = true
  }

  sealed trait WriteResult
  case object Complete extends WriteResult
  case object Incomplete extends WriteResult
  case class WriteError(t: Exception) extends WriteResult // EOF signals normal termination
}

private[nio1] abstract class NIO1HeadStage(ch: SelectableChannel,
                                         loop: SelectorLoop,
                                          key: SelectionKey) extends ChannelHead
{
  import NIO1HeadStage._

  override def name: String = "NIO1 ByteBuffer Head Stage"

  ///  channel reading bits //////////////////////////////////////////////

  private var readPromise: Promise[ByteBuffer] = null

  // Called by the selector loop when this channel has data to read
  final def readReady(scratch: ByteBuffer): Unit = {
    // assert(Thread.currentThread() == loop,
    //       s"Expected to be called only by SelectorLoop thread, was called by ${Thread.currentThread.getName}")

    val r = performRead(scratch)
    unsetOp(SelectionKey.OP_READ)

    // if we successfully read some data, unset the interest and
    // complete the promise, otherwise fail appropriately
    r match {
      case Success(_)   =>
        val p = readPromise
        readPromise = null
        if (!p.tryComplete(r)) p match {
          case p: PipeClosedPromise[_] => // NOOP: the channel was closed: data not needed.
          case other =>
            val msg = this.getClass.getName() +
              " is in an inconsistent state: readPromise already completed: " +
              p.toString
            closeWithError(new IllegalStateException(msg))
        }

      case Failure(e) =>
        val ee = checkError(e)
        sendInboundCommand(Disconnected)
        closeWithError(ee)    // will complete the promise with the error
    }
  }

  final override def readRequest(size: Int): Future[ByteBuffer] = {
    logger.trace(s"NIOHeadStage received a read request of size $size")
    val p = Promise[ByteBuffer]

    loop.executeTask(new Runnable {
      override def run(): Unit = {
        if (readPromise == null) {
          readPromise = p
          setOp(SelectionKey.OP_READ)
        }
        else readPromise match {
          case f :PipeClosedPromise[ByteBuffer] => p.tryCompleteWith(f.future)
          case _                    =>
            p.tryFailure(new IllegalStateException("Cannot have more than one pending read request"))
        }
      }
    })

    p.future

    
  }

  /// channel write bits /////////////////////////////////////////////////

  // these should only be accessed by the SelectorLoop thread
  private var writeData: Array[ByteBuffer] = null
  private var writePromise: Promise[Unit] = null

  // Called by the selector loop when this channel can be written to
  final def writeReady(scratch: ByteBuffer): Unit = {
    //assert(Thread.currentThread() == loop,
    //       s"Expected to be called only by SelectorLoop thread, was called by ${Thread.currentThread.getName}")

    val buffers = writeData // get a local reference so we don't hit the volatile a lot
    performWrite(scratch, buffers) match {
      case Complete =>
        writeData = null
        unsetOp(SelectionKey.OP_WRITE)
        val p = writePromise
        writePromise = null
        p.trySuccess(())

      case Incomplete => /* Need to wait for another go around to try and send more data */
        BufferTools.dropEmpty(buffers)

      case WriteError(t) =>
        unsetOp(SelectionKey.OP_WRITE)
        sendInboundCommand(Disconnected)
        closeWithError(t)
    }
  }

  final override def writeRequest(data: ByteBuffer): Future[Unit] = writeRequest(data::Nil)

  final override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] = {
    logger.trace(s"NIO1HeadStage Write Request: $data")
    val p = Promise[Unit]
    loop.executeTask(new Runnable {
      override def run(): Unit = {
        if (writePromise == null) {
          val writes = data.toArray
          if (!BufferTools.checkEmpty(writes)) {  // Non-empty buffer
            writePromise = p
            writeData = writes
            setOp(SelectionKey.OP_WRITE)
            p.future
          }
          else p.trySuccess(())                   // Empty buffer, just return success
        } else writePromise match {
          case f :PipeClosedPromise[Unit] => p.tryCompleteWith(f.future)
          case p =>
            val t = new IllegalStateException("Cannot have more than one pending write request")
            logger.error(t)(s"Received bad write request: $p")
            p.tryFailure(t)
        }
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

  // Cleanup any read or write requests with the exception
  final override def closeWithError(t: Throwable): Unit = {
    try loop.executeTask(new Runnable {
      def run() = {
        logger.trace(s"closeWithError($t); readPromise: $readPromise, writePromise: $writePromise")

        if (t != EOF) logger.error(t)("Abnormal NIO1HeadStage termination")

        if (readPromise != null) readPromise.tryFailure(t)
        readPromise = new PipeClosedPromise(t)

        if (writePromise != null) writePromise.tryFailure(t)
        writePromise = new PipeClosedPromise(t)
        writeData = Array()

        if (key.isValid) key.interestOps(0)
        key.attach(null)
        ch.close()
      }
    })
    catch { case NonFatal(t2) =>
      logger.error(t)("Caught exception while closing channel") 
      t2.addSuppressed(t)
      readPromise = new PipeClosedPromise(t2)
      writePromise = new PipeClosedPromise(t2)
    }
  }

  /** Unsets a channel interest
   *  only to be called by the SelectorLoop thread
   **/
  private def unsetOp(op: Int) {
    // assert(Thread.currentThread() == loop,
    //       s"Expected to be called only by SelectorLoop thread, was called by ${Thread.currentThread.getName}")

    try {
      val ops = key.interestOps()
      if ((ops & op) != 0) {
        key.interestOps(ops & ~op)
      }
    } catch {
      case _: CancelledKeyException =>
        sendInboundCommand(Disconnected)
        closeWithError(EOF)
    }
  }

  // only to be called by the SelectorLoop thread
  private def setOp(op: Int) {
    // assert(Thread.currentThread() == loop,
    //       s"Expected to be called only by SelectorLoop thread, was called by ${Thread.currentThread.getName}")

    try {
      val ops = key.interestOps()
      if ((ops & op) == 0) {
        key.interestOps(ops | op)
      }
    } catch {
      case _: CancelledKeyException =>
        sendInboundCommand(Disconnected)
        closeWithError(EOF)
    }
  }
}
