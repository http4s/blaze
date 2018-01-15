package org.http4s.blaze.http.http2.client

import java.nio.ByteBuffer

import org.http4s.blaze.http.{Http2ClientSession, HttpRequest}
import org.http4s.blaze.http.HttpClientSession.{ReleaseableResponse, Status}
import org.http4s.blaze.http.http2._
import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage}
import org.log4s.getLogger

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

private final class ClientSessionImpl(
    tailStage: TailStage[ByteBuffer],
    localSettings: ImmutableHttp2Settings,
    remoteSettings: MutableHttp2Settings,
    flowStrategy: FlowStrategy,
    parentExecutor: ExecutionContext)
  extends Http2ClientSession {

  private[this] val logger = getLogger
  private[this] val connection = new  ConnectionImpl(
    tailStage = tailStage,
    localSettings = localSettings,
    remoteSettings = remoteSettings,
    flowStrategy = flowStrategy,
    inboundStreamBuilder = None,
    parentExecutor = parentExecutor
  )

  override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = {
    logger.debug(s"Dispatching request: $request")
    val tail = new ClientStage(request, parentExecutor)
    val head = connection.newOutboundStream()
    LeafBuilder(tail).base(head)
    head.sendInboundCommand(Command.Connected)

    tail.result
  }

  override def quality: Double = connection.quality

  override def ping(): Future[Duration] = connection.ping

  override def status: Status = connection.status

  /** Close the session.
    *
    * This will generally entail closing the socket connection.
    */
  override def close(within: Duration): Future[Unit] =
    connection.drainSession(within)
}
