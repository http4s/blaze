package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.Http2Connection.Closing

import scala.concurrent.Promise

// TODO: this should be generalized to the server as well
private class OutboundStreamState(session: SessionCore) extends Http2StreamState(session) {
  private[this] var lazyStreamId: Int = -1
  private[this] var lazyFlowWindow: StreamFlowWindow = null

  override def streamId: Int = {
    if (lazyStreamId != -1) lazyStreamId
    else throw new IllegalStateException("Stream uninitialized")
  }

  override def flowWindow: StreamFlowWindow = {
    if (lazyFlowWindow != null) lazyFlowWindow
    else throw new IllegalStateException("Stream uninitialized")
  }

  // We need to establish whether the stream has been initialized yet and try to acquire a new ID if not
  override protected def invokeStreamWrite(msg: StreamMessage, p: Promise[Unit]): Unit = {
    if (lazyStreamId != -1) {
      super.invokeStreamWrite(msg, p)
    } else if (session.state.isInstanceOf[Closing]) {
      // Before we initialized the stream, we began to drain or were closed.
      val ex = Http2Exception.REFUSED_STREAM.goaway("Session closed before stream was initialized")
      p.tryFailure(ex)
      ()
    } else {
      session.streamManager.registerOutboundStream(this) match {
        case Some(streamId) =>
          lazyStreamId = streamId
          lazyFlowWindow = session.sessionFlowControl.newStreamFlowWindow(streamId)
          logger.debug(s"Created new OutboundStream with id $streamId. ${session.streamManager.size} streams.")
          super.invokeStreamWrite(msg, p)

        case None =>
          // TODO: Out of stream IDs. We need to switch to draining
          val ex = Http2Exception.REFUSED_STREAM.rst(0, "Session is out of outbound stream IDs")
          p.tryFailure(ex)
          ()
      }
    }
  }
}