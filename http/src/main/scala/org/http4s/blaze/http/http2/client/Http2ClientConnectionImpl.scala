package org.http4s.blaze.http.http2.client

import java.nio.ByteBuffer

import org.http4s.blaze.http.{Http2ClientSession, HttpRequest}
import org.http4s.blaze.http.HttpClientSession.ReleaseableResponse
import org.http4s.blaze.http.http2._
import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage}

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

private final class Http2ClientConnectionImpl(
    tailStage: TailStage[ByteBuffer],
    localSettings: ImmutableHttp2Settings, // the settings of this side
    remoteSettings: MutableHttp2Settings, // the settings of their side
    flowStrategy: FlowStrategy,
    parentExecutor: ExecutionContext)
  extends Http2ConnectionImpl(
    isClient = true,
    tailStage = tailStage,
    localSettings = localSettings,
    remoteSettings = remoteSettings,
    flowStrategy = flowStrategy,
    inboundStreamBuilder = _ => None,
    parentExecutor = parentExecutor
  ) with Http2ClientSession
  with Http2ClientConnection {

  override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = {
    logger.debug(s"Dispatching request: $request")
    val tail = new Http2ClientStage(request, parentExecutor)
    val head = newOutboundStream()
    LeafBuilder(tail).base(head)
    head.sendInboundCommand(Command.Connected)

    tail.result
  }

  /** Close the session.
    *
    * This will generally entail closing the socket connection.
    */
  override def close(within: Duration): Future[Unit] = drainSession(within)
}
