package org.http4s.blaze.pipeline.stages

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.{SSLException, SSLEngine}

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.concurrent.{Promise, Future}
import scala.util.{Try, Failure, Success}

import org.http4s.blaze.pipeline.MidStage
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.util.Execution._
import org.http4s.blaze.util.ScratchBuffer
import org.http4s.blaze.util.BufferTools._



final class SSLStage(engine: SSLEngine) extends MidStage[ByteBuffer, ByteBuffer] {
  import SSLStage._

  def name: String = s"SSLStage"

  private val maxNetSize = engine.getSession.getPacketBufferSize

  private val maxBuffer = math.max(maxNetSize, engine.getSession.getApplicationBufferSize)

  @volatile
  private var readLeftover: ByteBuffer = null

  def readRequest(size: Int): Future[ByteBuffer] = {
    val p = Promise[ByteBuffer]

    channelRead(size).onComplete {
      case Success(b) => readLoop(b, size, new ListBuffer[ByteBuffer], p)
      case f: Failure[_] => p.tryComplete(f.asInstanceOf[Failure[ByteBuffer]])
    }(directec)
    p.future
  }

  override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] = {
    val p = Promise[Unit]
    writeLoop(data.toArray, new ListBuffer, p)
    p.future
  }

  override def writeRequest(data: ByteBuffer): Future[Unit] = {
    val p = Promise[Unit]
    writeLoop(Array(data), new ListBuffer, p)
    p.future
  }

  /** Perform the SSL Handshake
    *
    * I would say this is by far the most precarious part of this library.
    *
    * @param data inbound ByteBuffer. Should be empty for write based handshakes.
    * @return any leftover inbound data.
    */
  private def sslHandshake(data: ByteBuffer, r: HandshakeStatus): Future[ByteBuffer] = {
    r match {
      case HandshakeStatus.NEED_UNWRAP =>
        try {
          val o = getScratchBuffer(maxBuffer)
          val r = engine.unwrap(data, o)

          if (r.getStatus == Status.BUFFER_UNDERFLOW) {
            channelRead().flatMap { b =>
              val sum = concatBuffers(data, b)
              sslHandshake(sum, r.getHandshakeStatus)
            }(trampoline)
          }
          else sslHandshake(data, r.getHandshakeStatus)
        } catch {
          case t: SSLException =>
            logger.warn(t)("SSLException in SSL handshake")
            Future.failed(t)

          case t: Throwable =>
            logger.error(t)("Error in SSL handshake. HandshakeStatus coming in: " + r)
            Future.failed(t)
        }

      case HandshakeStatus.NEED_TASK => // TODO: do we want to unwrap here? What of the data?
        try {
          runTasks()
          sslHandshake(data, engine.getHandshakeStatus)
        } catch {
          case t: SSLException =>
            logger.warn(t)("SSLException in SSL handshake while running tasks")
            Future.failed(t)

          case t: Throwable =>
            logger.error(t)("Error running handshake tasks")
            Future.failed(t)
        }

      case HandshakeStatus.NEED_WRAP =>
        try {
          val o = getScratchBuffer(maxBuffer)
          val r = engine.wrap(emptyBuffer, o)
          o.flip()

          if (r.bytesProduced() < 1) logger.warn(s"SSL Handshake WRAP produced 0 bytes.\n$r")

          channelWrite(copyBuffer(o))
            .flatMap { _ => sslHandshake(data, r.getHandshakeStatus) }(trampoline)
        } catch {
          case t: SSLException =>
            logger.warn(t)("SSLException during handshake")
            Future.failed(t)

          case t: Throwable =>
            logger.warn(t)("Error in SSL handshake")
            Future.failed(t)
        }

      case _ => Future.successful(data)
    }
  }

  // If we have at least one output buffer, we won't read more data until another request is made
  private def readLoop(buffer: ByteBuffer, size: Int, out: ListBuffer[ByteBuffer], p: Promise[ByteBuffer]): Unit = {
    // Consolidate buffers if they exist
    val b = concatBuffers(readLeftover, buffer)
    readLeftover = null

    var bytesRead = 0
    val o = getScratchBuffer(maxBuffer)

    @tailrec
    def go(): Unit = {
      val r = engine.unwrap(b, o)

      if (r.bytesProduced() > 0) {
        bytesRead += r.bytesProduced()
        o.flip()
        out += copyBuffer(o)
        o.clear()
      }

      logger.debug(s"SSL Read Request Status: $r, $o")

      r.getHandshakeStatus match {
        case HandshakeStatus.NOT_HANDSHAKING =>

          r.getStatus() match {
            case Status.OK => go()    // successful decrypt, continue

            case Status.BUFFER_UNDERFLOW => // Need more data
              if (b.hasRemaining) {   // TODO: stash the buffer. I don't like this, but is there another way?
                readLeftover = b
              }

              if (!out.isEmpty) {          // We got some data so send it
                p.success(joinBuffers(out))
              }
              else {
                val readsize = if (size > 0) size - bytesRead else size
                channelRead(math.min(readsize, maxNetSize)).onComplete {
                  case Success(b) => readLoop(b, readsize, out, p)
                  case Failure(f) => p.tryFailure(f)
                }(trampoline)
              }

            // It is up to the next stage to call shutdown, if that is what they want
            case Status.CLOSED =>
              if (!out.isEmpty) p.success(joinBuffers(out))
              else p.failure(EOF)

            case Status.BUFFER_OVERFLOW =>  // resize and go again
              p.tryComplete(invalidPosition("Buffer overflow in readLoop"))
          }

        case _ => // must be handshaking.
          sslHandshake(buffer, r.getHandshakeStatus).onComplete {
            case Success(data) => readLoop(b, size - bytesRead, out, p)
            case f@ Failure(_) => p.tryComplete(f)
          }(trampoline)
      }
    }

    try go()
    catch {
      case t: SSLException =>
        logger.warn(t)("SSLException during read loop")
        Future.failed(t)

      case t: Throwable =>
        logger.warn(t)("Error in SSL read loop")
        p.tryFailure(t)
    }
  }

  private def writeLoop(buffers: Array[ByteBuffer], out: ListBuffer[ByteBuffer], p: Promise[Unit]): Unit = {
    val o = getScratchBuffer(maxBuffer)
    @tailrec
    def go(): Unit = {    // We try and encode the data buffer by buffer until its gone
      o.clear()
      val r = engine.wrap(buffers, o)

      logger.debug(s"Write request result: $r, $o")

      r.getHandshakeStatus() match {
        case HandshakeStatus.NOT_HANDSHAKING =>

          if (o.position() > 0) { // Accumulate any encoded bytes for output
            o.flip()
            out += copyBuffer(o)
            o.clear()
          }

          r.getStatus() match {
            case Status.OK =>   // Successful encode
              if (checkEmpty(buffers)) p.completeWith(channelWrite(out))
              else go()

            case Status.CLOSED =>
              if (!out.isEmpty) p.completeWith(channelWrite(out))
              else p.tryFailure(EOF)

            case Status.BUFFER_OVERFLOW => // Should always have a large enough buffer
              p.tryComplete(invalidPosition("Buffer Overflow in writeLoop"))

            case Status.BUFFER_UNDERFLOW => // Need more data. Should probably never get here
              p.completeWith(channelWrite(out))
          }

        // r.getHandshakeStatus()
        case _ => // need to handshake

          def continue(r: Try[ByteBuffer]): Unit = r match {
            case Success(b) =>    // In reality, we shouldn't get any data back with a reasonable protocol.
              val old = readLeftover
              if (old != null && old.hasRemaining && b.hasRemaining) {
                readLeftover = concatBuffers(old, b)
              }
              else if (b.hasRemaining) {
                readLeftover = b
              }

              writeLoop(buffers, out, p)
            case Failure(t) => p.tryFailure(t)
          }

          def getInputBuffer() = {   // Get any pending read data for the buffer.
            val leftovers = readLeftover
            if (leftovers != null) {
              readLeftover = null
              leftovers
            } else emptyBuffer
          }

          if (o.position() > 0) { // need to send out some data first, then continue the handshake
            o.flip()
            channelWrite(copyBuffer(o))
              .flatMap { _ => sslHandshake(getInputBuffer(), r.getHandshakeStatus) }(trampoline)
              .onComplete(continue)(trampoline)

          } else sslHandshake(getInputBuffer(), r.getHandshakeStatus).onComplete(continue)(trampoline)
      }
    }

    try go()
    catch {
      case t: SSLException =>
        logger.warn(t)("SSLException during writeLoop")
        Future.failed(t)

      case t: Throwable =>
        logger.error(t)("Error in SSL writeLoop")
        p.tryFailure(t)
    }
  }

  private def invalidPosition(pos: String): Failure[Nothing] = {
    val e = new Exception("Invalid position: end of write loop")
    logger.error(e)(pos)
    Failure(e)
  }

  private def runTasks() {
    var t = engine.getDelegatedTask
    while(t != null) {
      t.run()
      t = engine.getDelegatedTask
    }
  }
}

private object SSLStage extends ScratchBuffer
