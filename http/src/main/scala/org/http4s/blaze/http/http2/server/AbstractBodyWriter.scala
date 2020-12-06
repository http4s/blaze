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

  protected def fail(cause: Throwable): Unit

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
    } else if (!buffer.hasRemaining) InternalWriter.CachedSuccess
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

  final override def close(cause: Option[Throwable]): Future[Finished] = {
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
    else
      cause match {
        case Some(ex) =>
          fail(ex)
          InternalWriter.CachedSuccess

        case None =>
          if (hsToFlush != null) {
            val frame = HeadersFrame(Priority.NoPriority, true, hsToFlush)
            flushMessage(frame)
          } else {
            val frame = DataFrame(true, BufferTools.emptyBuffer)
            flushMessage(frame)
          }
      }
  }
}
