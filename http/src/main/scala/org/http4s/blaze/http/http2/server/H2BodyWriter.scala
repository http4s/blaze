package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.{DataFrame, HeadersFrame, Priority, StreamMessage}
import org.http4s.blaze.http.{BodyWriter, Headers, InternalWriter}
import org.http4s.blaze.util.BufferTools

import scala.concurrent.Future

private abstract class H2BodyWriter(private var hs: Headers) extends BodyWriter {
  override type Finished = Unit

  private[this] val lock: Object = this
  private[this] var closed = false

  // must be called while holding the lock
  private[this] def takeHeaders: Headers = {
    val taken = hs
    hs = null
    taken
  }

  protected def flushMessage(msg: StreamMessage): Future[Unit]

  protected def flushMessage(msg: Seq[StreamMessage]): Future[Unit]

  final override def write(buffer: ByteBuffer): Future[Unit] = {
    var hsToFlush: Headers = null
    val wasClosed = lock.synchronized {
      if (closed) true
      else {
        hsToFlush = takeHeaders
        false
      }
    }

    if (wasClosed) InternalWriter.closedChannelException
    else if (hsToFlush != null) {
      val hs = HeadersFrame(Priority.NoPriority, false, hsToFlush)
      if (!buffer.hasRemaining) flushMessage(hs)
      else {
        val bodyFrame = DataFrame(false, buffer)
        flushMessage(hs :: bodyFrame :: Nil)
      }
    }
    else if (!buffer.hasRemaining) InternalWriter.cachedSuccess
    else {
      val bodyFrame = DataFrame(false, buffer)
      flushMessage(bodyFrame)
    }
  }

  final override def flush(): Future[Unit] = {
    var hsToFlush: Headers = null
    val wasClosed = lock.synchronized {
      if (closed) true
      else {
        hsToFlush = takeHeaders
        false
      }
    }
    if (wasClosed) InternalWriter.closedChannelException
    else if (hsToFlush == null) InternalWriter.cachedSuccess
    else {
      // need to flush the headers
      val hs = HeadersFrame(Priority.NoPriority, false, hsToFlush)
      flushMessage(hs)
    }
  }

  final override def close(): Future[Finished] = {
    var hsToFlush: Headers = null
    val wasClosed = lock.synchronized {
      if (closed) true
      else {
        hsToFlush = takeHeaders
        closed = true
        false
      }
    }
    if (wasClosed) InternalWriter.closedChannelException
    else if (hsToFlush != null) {
      val frame = HeadersFrame(Priority.NoPriority, true, hsToFlush)
      flushMessage(frame)
    } else {
      val frame = DataFrame(endStream = true, BufferTools.emptyBuffer)
      flushMessage(frame)
    }
  }
}
