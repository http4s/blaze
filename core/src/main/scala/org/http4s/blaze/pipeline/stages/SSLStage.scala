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

package org.http4s.blaze.pipeline.stages

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.{SSLEngine, SSLException}
import scala.annotation.tailrec
import scala.collection.mutable.{ArrayBuffer, Buffer, ListBuffer}
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal
import org.http4s.blaze.pipeline.MidStage
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.util
import org.http4s.blaze.util.{
  BufferTools,
  Execution,
  SerialExecutionContext,
  ThreadLocalScratchBuffer
}
import org.http4s.blaze.util.BufferTools._

private object SSLStage {
  private[this] val scratchBuffer: ThreadLocalScratchBuffer =
    new ThreadLocalScratchBuffer(useDirect = false)

  private def getScratchBuffer(size: Int) =
    scratchBuffer.getScratchBuffer(size)

  // Delayed read and write operations
  private sealed trait DelayedOp
  private case class DelayedRead(size: Int, p: Promise[ByteBuffer]) extends DelayedOp
  private case class DelayedWrite(data: Array[ByteBuffer], p: Promise[Unit]) extends DelayedOp

  private sealed trait SSLResult
  private case object SSLSuccess extends SSLResult
  private case class SSLNeedHandshake(r: HandshakeStatus) extends SSLResult
  private case class SSLFailure(t: Throwable) extends SSLResult
}

final class SSLStage(engine: SSLEngine, maxWrite: Int = 1024 * 1024)
    extends MidStage[ByteBuffer, ByteBuffer] {
  import SSLStage._

  def name: String = "SSLStage"

  // We use a serial executor to ensure single threaded behavior. This makes
  // things easy to read and dramatically reduces the chances of holding a lock
  // while completing a promise.
  private[this] val serialExec = new SerialExecutionContext(Execution.directec) {
    override def reportFailure(cause: Throwable): Unit = {
      logger.error(cause)("Failure during SSL operation. Aborting pipeline.")
      closePipeline(Some(cause))
      handshakeFailure(cause)
    }
  }

  override protected def stageShutdown(): Unit =
    try {
      engine.closeInbound()
      engine.closeOutbound()
    } catch {
      case e: SSLException =>
        // Ignore cleanup errors. Example: With JDK SSLContextImpl, if the connection is closed before even handshake
        // began(like port scanning), an SSLException might be thrown.
        logger.debug(e)("Error while closing SSL Engine")
    }

  private[this] val maxNetSize = engine.getSession.getPacketBufferSize
  private[this] val maxBuffer = math.max(maxNetSize, engine.getSession.getApplicationBufferSize)

  // /////////// State maintained by the SSLStage //////////////////////
  private[this] val handshakeQueue = new ListBuffer[DelayedOp]
  private[this] var readLeftover: ByteBuffer = null
  // ///////////////////////////////////////////////////////////////////

  // ///////////////////////////////////////////////////////////////////

  override def writeRequest(data: collection.Seq[ByteBuffer]): Future[Unit] =
    writeArray(data.toArray)

  override def writeRequest(data: ByteBuffer): Future[Unit] =
    writeArray(Array(data))

  override def readRequest(size: Int): Future[ByteBuffer] = {
    val p = Promise[ByteBuffer]()
    serialExec.execute(new Runnable { def run() = doRead(size, p) })
    p.future
  }

  // ///////////////////////////////////////////////////////////////////

  private[this] def writeArray(data: Array[ByteBuffer]): Future[Unit] = {
    val p = Promise[Unit]()
    serialExec.execute(new Runnable { def run(): Unit = doWrite(data, p) })
    p.future
  }

  // All processes that start a handshake will add elements to the queue
  private[this] def inHandshake: Boolean = handshakeQueue.nonEmpty

  // MUST be called from within the serial executor
  private[this] def takeQueuedBytes(): ByteBuffer =
    if (readLeftover != null) {
      val b = readLeftover
      readLeftover = null
      b
    } else emptyBuffer

  // Must only be called from within the serial executor
  private[this] def doRead(size: Int, p: Promise[ByteBuffer]): Unit = {
    val start = System.nanoTime
    if (inHandshake) handshakeQueue += DelayedRead(size, p)
    else {
      val out = new ArrayBuffer[ByteBuffer]
      val b = takeQueuedBytes()
      val r = readLoop(b, out)

      if (b.hasRemaining())
        readLeftover = b

      r match {
        case SSLSuccess if out.nonEmpty =>
          p.success(joinBuffers(out))
          ()

        case SSLSuccess => // buffer underflow and no data to send
          channelRead(if (size > 0) math.max(size, maxNetSize) else size)
            .onComplete {
              case Success(buff) =>
                readLeftover = concatBuffers(readLeftover, buff)
                doRead(size, p)

              case Failure(t) => p.failure(t); ()
            }(serialExec)

        case SSLNeedHandshake(r) =>
          handshakeQueue += DelayedRead(size, p)
          val data = takeQueuedBytes()
          sslHandshake(data, r)
          ()

        case SSLFailure(t) => p.failure(t); ()
      }
    }
    logger.trace(s"${engine.##}: doRead completed in ${System.nanoTime - start}ns")
  }

  // cleans up any pending requests
  private[this] def handshakeFailure(t: Throwable): Unit = {
    val start = System.nanoTime
    val results = handshakeQueue.result()
    handshakeQueue.clear()
    if (results.isEmpty)
      logger.error(t)(s"Handshake failure with no pending results")
    else
      results.foreach {
        case DelayedRead(_, p) => p.failure(t)
        case DelayedWrite(_, p) => p.failure(t)
      }
    logger.trace(s"${engine.##}: handshakeFailure completed in ${System.nanoTime - start}ns")
  }

  // Perform the SSL Handshake and then continue any pending operations.
  // There should be at least one pending operation as this is only started
  // after such an operation is stashed.
  private[this] def sslHandshake(data: ByteBuffer, r: HandshakeStatus): Unit = {
    @tailrec
    def sslHandshakeLoop(data: ByteBuffer, r: HandshakeStatus): Unit =
      r match {
        case HandshakeStatus.NEED_UNWRAP =>
          val o = getScratchBuffer(maxBuffer)
          val r = engine.unwrap(data, o)

          r.getStatus match {
            case Status.OK =>
              sslHandshakeLoop(data, r.getHandshakeStatus)

            case Status.BUFFER_UNDERFLOW =>
              channelRead().onComplete {
                case Success(b) =>
                  sslHandshake(concatBuffers(data, b), r.getHandshakeStatus)
                case Failure(t) => handshakeFailure(t)
              }(serialExec)

            case Status.CLOSED => // happens if the handshake fails for some reason
              handshakeFailure(new SSLException(s"SSL Closed"))

            case Status.BUFFER_OVERFLOW =>
              handshakeFailure(util.bug(s"Unexpected status: ${Status.BUFFER_OVERFLOW}"))
          }

        case HandshakeStatus.NEED_TASK =>
          runTasks()
          sslHandshakeLoop(data, engine.getHandshakeStatus)

        case HandshakeStatus.NEED_WRAP =>
          val o = getScratchBuffer(maxBuffer)
          val r = engine.wrap(emptyBuffer, o)
          logger.trace(s"SSL Handshake Status after wrap: $r")
          o.flip()

          if (r.bytesProduced < 1 && r.getHandshakeStatus != HandshakeStatus.FINISHED)
            handshakeFailure(new SSLException(s"SSL Handshake WRAP produced 0 bytes: $r"))

          // prevents infinite loop, see: https://bugs.openjdk.java.net/browse/JDK-8220703
          if (r.bytesProduced > 0 || r.getHandshakeStatus != HandshakeStatus.NEED_WRAP)
            channelWrite(copyBuffer(o)).onComplete {
              case Success(_) => sslHandshake(data, r.getHandshakeStatus)
              case Failure(t) => handshakeFailure(t)
            }(serialExec)

        // Finished with the handshake: continue what we were doing.
        case HandshakeStatus.FINISHED | HandshakeStatus.NOT_HANDSHAKING =>
          assert(readLeftover == null)
          readLeftover = data
          val pendingOps = handshakeQueue.result(); handshakeQueue.clear()
          logger.trace(s"Submitting backed up ops: $pendingOps")
          pendingOps.foreach {
            case DelayedRead(sz, p) => doRead(sz, p)
            case DelayedWrite(d, p) => doWrite(d, p)
          }

        case unexpected =>
          // This warns as unreachable on Scala 3 / Java 8, but does
          // not warn on Scala 2, so we can't use @nowarn.

          // Java 9 adds NEED_UNWRAP_AGAIN, which only applies to DTLS
          handshakeFailure(util.bug(s"Unknown status: ${unexpected}"))
      }

    val start = System.nanoTime
    try sslHandshakeLoop(data, r)
    catch {
      case t: SSLException =>
        logger.warn(t)("SSLException in SSL handshake")
        handshakeFailure(t)

      case NonFatal(t) =>
        logger.error(t)("Error in SSL handshake")
        handshakeFailure(t)
    }
    logger.trace(s"${engine.##}: sslHandshake completed in ${System.nanoTime - start}ns")
  }

  // Read as much data from the buffer, `b` as possible and put the result in
  // the accumulator `out`. It should only modify its arguments
  private[this] def readLoop(b: ByteBuffer, out: Buffer[ByteBuffer]): SSLResult = {
    val scratch = getScratchBuffer(maxBuffer)

    @tailrec
    def goRead(): SSLResult = {
      val r = engine.unwrap(b, scratch)
      logger.debug(s"SSL Read Request Status: $r, $scratch")

      if (r.bytesProduced > 0) {
        scratch.flip()
        out += copyBuffer(scratch)
        scratch.clear()
      }

      r.getHandshakeStatus match {
        case HandshakeStatus.NOT_HANDSHAKING =>
          r.getStatus match {
            case Status.OK => goRead() // successful decrypt, continue

            case Status.BUFFER_UNDERFLOW => // Need more data
              SSLSuccess

            case Status.CLOSED =>
              if (out.nonEmpty) SSLSuccess
              else SSLFailure(EOF)

            case Status.BUFFER_OVERFLOW => // resize and go again
              SSLFailure(new Exception("Shouldn't get here: Buffer overflow in readLoop"))
          }

        case _ => // must need to handshake
          if (out.nonEmpty) SSLSuccess
          else SSLNeedHandshake(r.getHandshakeStatus)
      }
    }

    try goRead()
    catch {
      case t: SSLException =>
        logger.trace(t)("SSLException during read loop")
        SSLFailure(t)

      case NonFatal(t) =>
        logger.trace(t)("Error in SSL read loop")
        SSLFailure(t)
    }
  }

  private[this] def doWrite(data: Array[ByteBuffer], p: Promise[Unit]): Unit = {
    val start = System.nanoTime
    if (inHandshake) {
      handshakeQueue += DelayedWrite(data, p)
      ()
    } else {
      val out = new ArrayBuffer[ByteBuffer]
      writeLoop(data, out) match {
        case SSLSuccess if BufferTools.checkEmpty(data) =>
          p.completeWith(channelWrite(out.toSeq)); ()

        case SSLSuccess => // must have more data to write
          channelWrite(out.toSeq).onComplete {
            case Success(_) => doWrite(data, p)
            case Failure(t) => p.failure(t); ()
          }(serialExec)

        case SSLNeedHandshake(r) =>
          handshakeQueue += DelayedWrite(data, p)
          val readData = takeQueuedBytes()
          if (out.nonEmpty) // need to write
            channelWrite(out.toSeq).onComplete {
              case Success(_) => sslHandshake(readData, r)
              case Failure(t) => p.failure(t)
            }(serialExec)
          else sslHandshake(readData, r)

        case SSLFailure(t) => p.failure(t); ()
      }
    }
    logger.trace(s"${engine.##}: continueWrite completed in ${System.nanoTime - start}ns")
  }

  // Encrypt as much of `buffers` as possible, placing the result in `out`.
  private[this] def writeLoop(buffers: Array[ByteBuffer], out: Buffer[ByteBuffer]): SSLResult = {
    val o = getScratchBuffer(maxBuffer)
    @tailrec
    def goWrite(b: Int): SSLResult = {
      // We try and encode the data buffer by buffer until its gone
      o.clear()
      val r = engine.wrap(buffers, o)

      if (o.position() > 0) { // Accumulate any encoded bytes for output
        o.flip()
        out += copyBuffer(o)
      }

      r.getHandshakeStatus() match {
        case HandshakeStatus.NOT_HANDSHAKING =>
          val buffered = b + r.bytesProduced()

          r.getStatus() match {
            case Status.OK => // Successful encode
              if (checkEmpty(buffers) || maxWrite > 0 && buffered > maxWrite)
                SSLSuccess
              else goWrite(buffered)

            case Status.CLOSED => SSLFailure(EOF)

            case s =>
              SSLFailure(new Exception(s"Invalid status in SSL writeLoop: $s"))
          }

        case _ => SSLNeedHandshake(r.getHandshakeStatus) // need to handshake
      }
    }

    try {
      val r = goWrite(0)
      BufferTools.dropEmpty(buffers) // let the GC have the empty buffers
      r
    } catch {
      case t: SSLException =>
        logger.trace(t)("SSLException during writeLoop")
        SSLFailure(t)

      case NonFatal(t) =>
        logger.trace(t)("Error in SSL writeLoop")
        SSLFailure(t)
    }
  }

  @tailrec
  private[this] def runTasks(): Unit = {
    val task = engine.getDelegatedTask()
    if (task != null) {
      task.run()
      runTasks()
    }
  }
}
