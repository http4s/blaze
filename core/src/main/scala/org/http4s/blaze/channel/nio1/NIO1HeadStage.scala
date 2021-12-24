/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.channel.nio1

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels._
import java.util.concurrent.RejectedExecutionException
import org.http4s.blaze.channel.ChannelHead
import org.http4s.blaze.pipeline.Command.{Disconnected, EOF}
import org.http4s.blaze.util
import org.http4s.blaze.util.BufferTools

import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success, Try}

private[nio1] object NIO1HeadStage {
  private val CachedSuccess = Success(())

  private sealed trait WriteResult
  private case object Complete extends WriteResult
  private case object Incomplete extends WriteResult
  private case class WriteError(t: Exception) extends WriteResult // EOF signals normal termination

  /** Performs the read operation
    *
    * @param scratch
    *   a ByteBuffer in which to load read data. The method doesn't take and ownership interest in
    *   the buffer, eg it's reference is not retained.
    * @return
    *   a `Try` representing successfully loading data into `scratch`, or the failure cause.
    */
  private def performRead(ch: NIO1ClientChannel, scratch: ByteBuffer, size: Int): Try[Unit] =
    try {
      scratch.clear()
      if (size >= 0 && size < scratch.remaining)
        scratch.limit(size)
      val bytes = ch.read(scratch)
      if (bytes < 0) Failure(EOF)
      else {
        scratch.flip()
        CachedSuccess
      }
    } catch {
      case _: ClosedChannelException => Failure(EOF)
      case e: IOException if ChannelHead.brokePipeMessages.contains(e.getMessage) =>
        Failure(EOF)
      case e: IOException => Failure(e)
    }

  /** Perform the write operation for this channel
    * @param buffers
    *   buffers to be written to the channel
    * @return
    *   a WriteResult that is one of Complete, Incomplete or WriteError(e: Exception)
    */
  private def performWrite(
      ch: NIO1ClientChannel,
      scratch: ByteBuffer,
      buffers: Array[ByteBuffer]): WriteResult =
    try
      if (BufferTools.areDirectOrEmpty(buffers)) {
        ch.write(buffers)
        if (util.BufferTools.checkEmpty(buffers)) Complete
        else Incomplete
      } else {
        // To sidestep the java NIO "memory leak" (see http://www.evanjones.ca/java-bytebuffer-leak.html)
        // We copy the data to the scratch buffer (which should be a direct ByteBuffer)
        // before the write. We then check to see how much data was written and fast-forward
        // the input buffers accordingly.
        // This is very similar to the pattern used by the Oracle JDK implementation in its
        // IOUtil class: if the provided buffers are not direct buffers, they are copied to
        // temporary direct ByteBuffers and written.
        @tailrec
        def writeLoop(): WriteResult = {
          scratch.clear()
          BufferTools.copyBuffers(buffers, scratch)
          scratch.flip()

          val written = ch.write(scratch)
          if (written > 0)
            assert(BufferTools.fastForwardBuffers(buffers, written))

          if (scratch.remaining > 0)
            // Couldn't write all the data.
            Incomplete
          else if (util.BufferTools.checkEmpty(buffers))
            // All data was written
            Complete
          else
            // May still be able to write more to the socket buffer.
            writeLoop()
        }

        writeLoop()
      }
    catch {
      case _: ClosedChannelException => WriteError(EOF)
      case e: IOException if ChannelHead.brokePipeMessages.contains(e.getMessage) =>
        WriteError(EOF)
      case e: IOException =>
        WriteError(e)
    }
}

private[nio1] final class NIO1HeadStage(
    ch: NIO1ClientChannel,
    selectorLoop: SelectorLoop,
    key: SelectionKey
) extends ChannelHead
    with Selectable {
  import NIO1HeadStage._

  override def name: String = "NIO1 ByteBuffer Head Stage"

  // State of the HeadStage. These should only be accessed from the SelectorLoop thread
  // will only be written to inside of 'closeWithError'
  private[this] var closedReason: Throwable = null

  private[this] var readPromise: Promise[ByteBuffer] = null
  private[this] var readSize: Int = -1

  private[this] var writeData: Array[ByteBuffer] = null
  private[this] var writePromise: Promise[Unit] = null

  final def opsReady(scratch: ByteBuffer): Unit = {
    val readyOps = key.readyOps
    if ((readyOps & SelectionKey.OP_READ) != 0) readReady(scratch)
    if ((readyOps & SelectionKey.OP_WRITE) != 0) writeReady(scratch)
  }

  // Called by the selector loop when this channel has data to read
  private[this] def readReady(scratch: ByteBuffer): Unit = {
    unsetOp(SelectionKey.OP_READ)

    if (readPromise != null)
      // if we successfully read some data, unset the interest and
      // complete the promise, otherwise fail appropriately
      performRead(ch, scratch, readSize) match {
        case Success(_) =>
          val buffer = BufferTools.copyBuffer(scratch)
          val p = readPromise
          readPromise = null
          p.success(buffer)
          ()

        case Failure(e) =>
          val p = readPromise
          readPromise = null
          p.failure(checkError(e))
          ()
      }
  }

  private[this] def writeReady(scratch: ByteBuffer): Unit = {
    val buffers = writeData // get a local reference so we don't hit the volatile a lot
    performWrite(ch, scratch, buffers) match {
      case Complete =>
        writeData = null
        unsetOp(SelectionKey.OP_WRITE)
        val p = writePromise
        writePromise = null
        if (p != null) {
          p.tryComplete(CachedSuccess)
          ()
        }

      case Incomplete =>
        // Need to wait for another go around to try and send more data
        BufferTools.dropEmpty(buffers)
        ()

      case WriteError(t) =>
        unsetOp(SelectionKey.OP_WRITE)
        val p = writePromise
        writePromise = null
        if (p != null) {
          p.failure(t)
          ()
        }
    }
  }

  // /  channel reading bits //////////////////////////////////////////////

  final override def readRequest(size: Int): Future[ByteBuffer] = {
    logger.trace(s"NIOHeadStage received a read request of size $size")
    val p = Promise[ByteBuffer]()

    selectorLoop.executeTask(new selectorLoop.LoopRunnable {
      override def run(scratchBuffer: ByteBuffer): Unit =
        if (closedReason != null) {
          p.failure(closedReason)
          ()
        } else if (readPromise == null)
          // First we try to just read data, and fall back to NIO notification
          // if we don't get any data back.
          performRead(ch, scratchBuffer, size) match {
            case Success(_) if scratchBuffer.remaining > 0 =>
              // We read some data. Need to copy it and send it on it's way.
              val data = BufferTools.copyBuffer(scratchBuffer)
              p.success(data)
              ()

            case Success(_) =>
              // No data available, so setup for NIO notification.
              readSize = size
              readPromise = p
              setOp(SelectionKey.OP_READ)
              ()

            case Failure(e) =>
              p.failure(checkError(e))
              ()
          }
        else {
          p.failure(new IllegalStateException("Cannot have more than one pending read request"))
          ()
        }
    })
    p.future
  }

  // / channel write bits /////////////////////////////////////////////////

  final override def writeRequest(data: ByteBuffer): Future[Unit] =
    writeRequest(data :: Nil)

  final override def writeRequest(data: collection.Seq[ByteBuffer]): Future[Unit] = {
    logger.trace(s"NIO1HeadStage Write Request: $data")
    val p = Promise[Unit]()
    selectorLoop.executeTask(new selectorLoop.LoopRunnable {
      override def run(scratch: ByteBuffer): Unit =
        if (closedReason != null) {
          p.failure(closedReason)
          ()
        } else if (writePromise == null) {
          val writes = data.toArray
          if (!BufferTools.checkEmpty(writes))
            // Non-empty buffers. First we check to see if we can immediately
            // write all the data to save a trip through the NIO event system.
            performWrite(ch, scratch, writes) match {
              case Complete =>
                p.tryComplete(CachedSuccess)
                ()

              case Incomplete =>
                // Need to be notified by NIO when we can write again.
                BufferTools.dropEmpty(writes)
                writePromise = p
                writeData = writes
                setOp(SelectionKey.OP_WRITE)
                p.future
                ()

              case WriteError(t) =>
                p.failure(t)
                ()
            }
          else {
            // Empty buffers, just return success.
            p.complete(CachedSuccess)
            ()
          }
        } else {
          val t = new IllegalStateException("Cannot have more than one pending write request")
          p.failure(t)
          ()
        }
    })

    p.future
  }

  // /////////////////////////////// Channel Ops ////////////////////////////////////////

  final override def close(cause: Option[Throwable]): Unit = doClosePipeline(cause)

  final override protected def doClosePipeline(cause: Option[Throwable]): Unit = {
    // intended to be called from within the SelectorLoop but if
    // it's closed it will be performed in the current thread
    def doClose(t: Throwable): Unit = {
      // this is the only place that writes to the variable
      if (closedReason == null)
        closedReason = t
      else if (closedReason != EOF && closedReason != t)
        closedReason.addSuppressed(t)

      if (readPromise != null) {
        readPromise.tryFailure(t)
        readPromise = null
      }

      if (writePromise != null) {
        writePromise.tryFailure(t)
        writePromise = null
      }

      writeData = null
      try ch.close()
      catch {
        case ex: IOException =>
          logger.warn(ex)("Unexpected IOException during channel close")
      }
      sendInboundCommand(Disconnected)
    }

    try
      selectorLoop.executeTask(new Runnable {
        def run(): Unit = {
          logger.trace(
            s"closeWithError($cause); readPromise: $readPromise, writePromise: $writePromise")
          val c = cause match {
            case Some(ex) =>
              logger.error(cause.get)("Abnormal NIO1HeadStage termination")
              ex
            case None => EOF
          }
          if (key.isValid) key.interestOps(0)
          key.attach(null)
          doClose(c)
        }
      })
    catch {
      case e: RejectedExecutionException =>
        logger.error(e)("Event loop closed. Closing in current thread.")
        doClose(cause.getOrElse(EOF))
    }
  }

  /** Unsets a channel interest only to be called by the SelectorLoop thread
    */
  private[this] def unsetOp(op: Int): Unit =
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
        close(None)
    }

  // only to be called by the SelectorLoop thread
  private[this] def setOp(op: Int): Unit =
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
        close(None)
    }
}
