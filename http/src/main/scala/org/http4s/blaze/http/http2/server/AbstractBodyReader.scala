package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer

import org.http4s.blaze.http.BodyReader
import org.http4s.blaze.http.http2.{DataFrame, HeadersFrame, StreamFrame}
import org.http4s.blaze.http.http2.Http2Exception._
import org.http4s.blaze.util.{BufferTools, Execution}
import org.log4s.getLogger

import scala.concurrent.Future
import scala.util.{Failure, Success}

// TODO: this could probably be shared with the client
/** Base implementation of the HTTP/2 [[BodyReader]]
  *
  * @param streamId the HTTP/2 stream id associated with this reader
  * @param length length of data expected, or -1 if unknown.
  */
private abstract class AbstractBodyReader(streamId: Int, length: Long) extends BodyReader {
  import AbstractBodyReader._

  private[this] var bytesRead = 0L
  private[this] var finished = false

  private[this] val logger = getLogger

  protected def channelRead(): Future[StreamFrame]

  protected def failed(ex: Throwable): Unit

  override def discard(): Unit = this.synchronized {
    finished = true
  }

  override def isExhausted: Boolean = this.synchronized(finished)

  def apply(): Future[ByteBuffer] = {
    if (isExhausted) BufferTools.emptyFutureBuffer
    else {
      channelRead().flatMap { frame =>
        // We use `result` to escape the lock before doing arbitrary things
        val result = AbstractBodyReader.this.synchronized {
          frame match {
            case DataFrame(endStream, bytes) =>
              finished = endStream
              bytesRead += bytes.remaining()
              if (length == UnknownLength || bytesRead <= length) {
                Success(bytes)
              } else {
                // overflow. This is a malformed message.
                val msg = s"Invalid content-length, expected: $length, received (thus far): $bytesRead"
                Failure(PROTOCOL_ERROR.rst(streamId, msg))
              }

            case HeadersFrame(_, true, ts) =>
              finished = true
              logger.info(s"Discarding trailer headers: $ts")
              Success(BufferTools.emptyBuffer)

            case HeadersFrame(_, false, _) =>
              // If their are trailers, they must not be followed by any more
              // messages in the HTTP message dispatch.
              // Rule 4 of a HTTP Request/Response Exchange
              // (https://tools.ietf.org/html/rfc7540#section-8.1)
              finished = true
              val msg = "Received non-final HEADERS frame while reading body."
              Failure(PROTOCOL_ERROR.rst(streamId, msg))
          }
        }

        result match {
          case Failure(e) =>
            logger.info(e)("While attempting to read body")
            failed(e)

          case Success(_) => // nop
        }
        Future.fromTry(result)
      }(Execution.trampoline)
    }
  }
}

private object AbstractBodyReader {
  val UnknownLength: Long = -1l
}
