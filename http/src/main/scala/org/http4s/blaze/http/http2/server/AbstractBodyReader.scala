package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer

import org.http4s.blaze.http.BodyReader
import org.http4s.blaze.http.http2.{DataFrame, HeadersFrame, StreamMessage}
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
              // trailers must be the final frame of the stream:
              // optionally, one HEADERS frame, followed by zero or more
              // CONTINUATION frames containing the trailer-part, if present (see
              // [RFC7230], Section 4.1.2).
              // https://tools.ietf.org/html/rfc7540#section-8.1
              finished = true
              val msg = "Received non-final HEADERS frame while reading body."
              Failure(PROTOCOL_ERROR.rst(streamId, msg))
          }
        }
        // TODO: change to the `.fromTry` API once we've dumped 2.10.x builds
        result match {
          case Failure(e) =>
            logger.info(e)("While attempting to read body")
            failed(e)
            Future.failed(e)

          case Success(v) =>
            Future.successful(v)
        }
      }(Execution.trampoline)
    }
  }
}

private object AbstractBodyReader {
  val UnknownLength: Long = -1l
}
