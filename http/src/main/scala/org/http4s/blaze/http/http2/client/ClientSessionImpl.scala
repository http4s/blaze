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

  override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = ???

  override def quality: Double = ???

  override def ping(): Future[Duration] = ???

  override def status: Status = ???

  /** Close the session.
    *
    * This will generally entail closing the socket connection.
    */
  override def close(within: Duration): Future[Unit] = ???
}
