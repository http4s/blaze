package org.http4s.blaze.http.http2.client

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

import org.http4s.blaze.http.{Http2ClientSession, HttpClientSession, HttpRequest}
import org.http4s.blaze.http.HttpClientSession.{ReleaseableResponse, Status}
import org.http4s.blaze.http.http2.Http2Connection.Closing
import org.http4s.blaze.http.http2._
import org.http4s.blaze.pipeline.{Command, HeadStage, LeafBuilder, TailStage}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}


private class Http2ClientConnectionImpl(
    tailStage: TailStage[ByteBuffer],
    localSettings: ImmutableHttp2Settings, // the settings of this side
    remoteSettings: MutableHttp2Settings, // the settings of their side
    flowStrategy: FlowStrategy,
    executor: ExecutionContext)
  extends Http2ConnectionImpl(
    tailStage,
    localSettings,
    remoteSettings,
    flowStrategy,
    executor
  ) with Http2ClientSession
  with Http2ClientConnection {

  override protected def newStreamManager(session: SessionCore): StreamManager =
    new ClientStreamManager(session)

  this.onClose.onComplete { _ =>
    tailStage.sendOutboundCommand(Command.Disconnect)
  }(executor)

  override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = {
    logger.debug(s"Dispatching request: $request")
    val tail = new Http2ClientStage(request, executor)
    val head = newOutboundStream()
    LeafBuilder(tail).base(head)
    head.sendInboundCommand(Command.Connected)

    tail.result
  }

  /** Get the status of session */
  override def status: Status = {
    if (state == Http2Connection.Running) {
      if (Core.streamManager.size < remoteSettings.maxConcurrentStreams) {
        HttpClientSession.Ready
      } else {
        HttpClientSession.Busy
      }
    } else {
      HttpClientSession.Closed
    }
  }

  /** Close the session.
    *
    * This will generally entail closing the socket connection.
    */
  override def close(within: Duration): Future[Unit] = drainSession(within)

  /** Create a new outbound stream
    *
    * Resources are not necessarily allocated to this stream, therefore it is
    * not guaranteed to succeed.
    */
  override def newOutboundStream(): HeadStage[StreamMessage] = {
    new OutboundStream
  }

  private class OutboundStream extends Http2StreamState(Core) {
    private[this] var lazyStreamId: Int = -1
    private[this] var lazyFlowWindow: StreamFlowWindow = null

    override def streamId: Int = {
      if (lazyStreamId != -1) {
        lazyStreamId
      } else {
        throw new IllegalStateException("Stream uninitialized")
      }
    }

    override def flowWindow: StreamFlowWindow = {
      if (lazyFlowWindow != null) lazyFlowWindow
      else throw new IllegalStateException("Stream uninitialized")
    }

    // We need to establish whether the stream has been initialized yet and try to acquire a new ID if not
    override protected def invokeStreamWrite(msg: StreamMessage, p: Promise[Unit]): Unit = {
      if (lazyStreamId != -1) {
        super.invokeStreamWrite(msg, p)
      } else if (state.isInstanceOf[Closing]) {
        // Before we initialized the stream, we began to drain or were closed.
        val ex = Http2Exception.REFUSED_STREAM.goaway("Session closed before stream was initialized")
        p.tryFailure(ex)
        ()
      } else {
        Core.streamManager.registerOutboundStream(this) match {
          case Some(streamId) =>
            lazyStreamId = streamId
            lazyFlowWindow = Core.sessionFlowControl.newStreamFlowWindow(streamId)
            logger.debug(s"Created new OutboundStream with id $streamId. ${Core.streamManager.size} streams.")
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
}
