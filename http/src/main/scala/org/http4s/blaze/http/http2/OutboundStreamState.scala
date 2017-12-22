package org.http4s.blaze.http.http2

import scala.concurrent.Promise
import scala.concurrent.duration.Duration

/** Representation of outbound streams
  *
  * For outbound streams we need a concrete StreamState to send messages
  * to, but we can't expect that we will have HEADERS to send right when
  * it is born, so we need to make the stream ID lazy since they must be
  * used in monotonically increasing order.
  *
  * @note this is a marker trait
  */

private trait OutboundStreamState extends StreamState

private final class OutboundStreamStateImpl(session: SessionCore)
  extends StreamStateImpl(session)
    with OutboundStreamState
{
  private[this] var lazyStreamId: Int = -1
  private[this] var lazyFlowWindow: StreamFlowWindow = null

  private[this] def initialized: Boolean = lazyStreamId != -1
  private[this] def uninitializedException(): Nothing = {
    throw new IllegalStateException("Stream uninitialized")
  }

  override def name: String = {
    val id = if (initialized) Integer.toString(streamId) else "uninitialized"
    s"OutboundStreamState($id)"
  }

  override def streamId: Int = {
    if (initialized) lazyStreamId
    else uninitializedException()
  }

  override def flowWindow: StreamFlowWindow = {
    if (initialized) lazyFlowWindow
    else uninitializedException()
  }

  // We need to establish whether the stream has been initialized yet and try to acquire a new ID if not
  override protected def invokeStreamWrite(msg: StreamMessage, p: Promise[Unit]): Unit = {
    if (initialized) {
      super.invokeStreamWrite(msg, p)
    } else if (session.state.closing) {
      // Before we initialized the stream, we began to drain or were closed.
      val ex = Http2Exception.REFUSED_STREAM.rst(0, "Session closed before stream was initialized")
      p.failure(ex)
      ()
    } else {
      session.streamManager.registerOutboundStream(this) match {
        case Some(freshId) =>
          // Note: it is assumed that these will both be initialized or neither are initialized
          lazyFlowWindow = session.sessionFlowControl.newStreamFlowWindow(freshId)
          lazyStreamId = freshId
          logger.debug(s"Created new OutboundStream with id $freshId. ${session.streamManager.size} streams.")
          super.invokeStreamWrite(msg, p)

        case None =>
          // Out of stream IDs so we make sure the session is starting to drain
          session.invokeDrain(Duration.Inf)
          val ex = Http2Exception.REFUSED_STREAM.rst(0, "Session is out of outbound stream IDs")
          p.failure(ex)
          ()
      }
    }
  }
}