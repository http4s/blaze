package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer

import org.http4s.blaze.http.BodyReader
import org.http4s.blaze.http.http2.{DataFrame, HeadersFrame, StreamMessage}
import org.http4s.blaze.http.http2.Http2Exception._
import org.http4s.blaze.util.{BufferTools, Execution}
import org.log4s.getLogger

import scala.concurrent.Future
import scala.util.{Failure, Success}

private abstract class H2BodyReader(streamId: Int, length: Long) extends BodyReader {
  private[this] var bytesRead = 0L
  private[this] var finished = false

  private[this] val lock: Object = this
  private[this] val logger = getLogger

  protected def channelRead(): Future[StreamMessage]

  protected def failed(ex: Throwable): Unit

  override def discard(): Unit = lock.synchronized {
    finished = true
  }

  override def isExhausted: Boolean = lock.synchronized(finished)

  def apply(): Future[ByteBuffer] = {
    if (isExhausted) BufferTools.emptyFutureBuffer
    else {
      channelRead().flatMap { frame =>
        // We use `result` to escape the lock before doing arbitrary things
        val result = lock.synchronized {
          frame match {
            case DataFrame(endStream, bytes) =>
              bytesRead += bytes.remaining()
              if (length != -1 && bytesRead > length) {
                // overflow. This is a stream error
                val msg = s"Invalid content-length, expected: $length, received (thus far): $bytesRead"
                Failure(PROTOCOL_ERROR.rst(streamId, msg))
              } else {
                finished = endStream
                Success(bytes)
              }

            case HeadersFrame(_, endStream, ts) =>
              logger.info(s"Discarding trailer headers: $ts")
              if (endStream) {
                finished = true
                Success(BufferTools.emptyBuffer)
              } else {
                // https://tools.ietf.org/html/rfc7540#section-5.1
                // trailing headers must be the end of the stream
                val msg = "Received headers which didn't end the stream."
                Failure(PROTOCOL_ERROR.rst(streamId, msg))
              }

            case other =>
              finished = true
              val msg = "Received invalid frame while accumulating body: " + other
              Failure(PROTOCOL_ERROR.rst(streamId, msg))
          }
        }
        result match {
          case f@Failure(e) =>
            logger.info(e)("While attempting to read body")
            failed(e)
            Future.fromTry(f)

          case s@Success(_) =>
            Future.fromTry(s)
        }
      }(Execution.trampoline)
    }
  }
}
