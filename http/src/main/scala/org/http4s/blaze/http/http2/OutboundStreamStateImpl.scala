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

package org.http4s.blaze.http.http2

import scala.concurrent.Promise
import scala.concurrent.duration.Duration

private abstract class OutboundStreamStateImpl(session: SessionCore)
    extends StreamStateImpl(session)
    with OutboundStreamState {
  private[this] var lazyStreamId: Int = -1
  private[this] var lazyFlowWindow: StreamFlowWindow = null

  private[this] def uninitializedException(): Nothing =
    throw new IllegalStateException("Stream uninitialized")

  protected def registerStream(): Option[Int]

  final override def initialized: Boolean = lazyStreamId != -1

  final override def name: String = {
    val id = if (initialized) Integer.toString(streamId) else "uninitialized"
    s"OutboundStreamState($id)"
  }

  final override def streamId: Int =
    if (initialized) lazyStreamId
    else uninitializedException()

  final override def flowWindow: StreamFlowWindow =
    if (initialized) lazyFlowWindow
    else uninitializedException()

  // We need to establish whether the stream has been initialized yet and try to acquire a new ID if not
  final override protected def invokeStreamWrite(msg: StreamFrame, p: Promise[Unit]): Unit =
    if (initialized)
      super.invokeStreamWrite(msg, p)
    else if (session.state.closing) {
      // Before we initialized the stream, we began to drain or were closed.
      val ex = Http2Exception.REFUSED_STREAM
        .rst(0, "Session closed before stream was initialized")
      p.failure(ex)
      ()
    } else
      registerStream() match {
        case Some(freshId) =>
          lazyFlowWindow = session.sessionFlowControl.newStreamFlowWindow(freshId)
          lazyStreamId = freshId
          logger.debug(s"Created new OutboundStream with id $freshId.")
          super.invokeStreamWrite(msg, p)

        case None =>
          // Out of stream IDs so we make sure the session is starting to drain
          session.invokeDrain(Duration.Inf)
          val ex = Http2Exception.REFUSED_STREAM
            .rst(0, "Session is out of outbound stream IDs")
          p.failure(ex)
          ()
      }
}
