package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.{DataFrame, HeadersFrame, Priority, StreamFrame}
import org.http4s.blaze.http.{BodyWriter, Headers, InternalWriter}
import org.http4s.blaze.util.BufferTools

import scala.concurrent.Future

private abstract class AbstractBodyWriter(private var hs: Headers) extends BodyWriter {
  final override type Finished = Unit

  // must be protected by synchronized on `this`
  private[this] var closed = false

  // must only be called while synchronized on `this`
  private[this] def takeHeaders(): Headers = {
    val taken = hs
    hs = null
    taken
  }

  protected def flushMessage(msg: StreamFrame): Future[Unit]

  protected def flushMessage(msg: Seq[StreamFrame]): Future[Unit]

  final override def write(buffer: ByteBuffer): Future[Unit] = {
    var hsToFlush: Headers = null
    val wasClosed = this.synchronized {
      if (closed) true
      else {
        hsToFlush = takeHeaders()
        false
      }
    }

    if (wasClosed) InternalWriter.ClosedChannelException
    else if (hsToFlush != null) {
      val hs = HeadersFrame(Priority.NoPriority, false, hsToFlush)
      if (!buffer.hasRemaining) flushMessage(hs)
      else {
        val bodyFrame = DataFrame(false, buffer)
        flushMessage(hs :: bodyFrame :: Nil)
      }
    }
    else if (!buffer.hasRemaining) InternalWriter.CachedSuccess
    else {
      val bodyFrame = DataFrame(false, buffer)
      flushMessage(bodyFrame)
    }
  }

  final override def flush(): Future[Unit] = {
    var hsToFlush: Headers = null
    val wasClosed = this.synchronized {
      if (closed) true
      else {
        hsToFlush = takeHeaders()
        false
      }
    }
    if (wasClosed) InternalWriter.ClosedChannelException
    else if (hsToFlush == null) InternalWriter.CachedSuccess
    else {
      // need to flush the headers
      val hs = HeadersFrame(Priority.NoPriority, false, hsToFlush)
      flushMessage(hs)
    }
  }

  final override def close(): Future[Finished] = {
    var hsToFlush: Headers = null
    val wasClosed = this.synchronized {
      if (closed) true
      else {
        hsToFlush = takeHeaders()
        closed = true
        false
      }
    }
    if (wasClosed) InternalWriter.ClosedChannelException
    else if (hsToFlush != null) {
      val frame = HeadersFrame(Priority.NoPriority, true, hsToFlush)
      flushMessage(frame)
    } else {
      val frame = DataFrame(true, BufferTools.emptyBuffer)
      flushMessage(frame)
    }
  }
}
