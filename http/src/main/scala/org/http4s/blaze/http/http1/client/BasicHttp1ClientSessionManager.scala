package org.http4s.blaze.http.http1.client

import java.nio.ByteBuffer

import org.http4s.blaze.channel.nio2.ClientChannelFactory
import org.http4s.blaze.http.{
  ClientSessionManager,
  HttpClientConfig,
  HttpClientSession,
  HttpRequest
}
import org.http4s.blaze.http.util.UrlTools.UrlComposition
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.pipeline.{Command, HeadStage, LeafBuilder}
import org.http4s.blaze.util.{Execution, FutureUnit}
import org.log4s.getLogger

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

private[http] final class BasicHttp1ClientSessionManager(config: HttpClientConfig)
    extends ClientSessionManager {
  private[this] val logger = getLogger
  private[this] val factory = new ClientChannelFactory(group = config.channelGroup)

  override def acquireSession(request: HttpRequest): Future[HttpClientSession] =
    UrlComposition(request.url) match {
      case Success(composition) =>
        logger.debug(
          s"Attempting to acquire session for address ${request.url} " +
            s"with url decomposition $composition")
        factory
          .connect(composition.getAddress)
          .map(connectPipeline(composition, _))(Execution.directec)

      case Failure(t) =>
        logger.debug(t)("Failed to build valid request")
        Future.failed(t)
    }

  // We just close all sessions
  override def returnSession(session: HttpClientSession): Unit = {
    // We orphan the future: don't care.
    session.close(Duration.Zero)
    ()
  }

  /** Close the `SessionManager` and free any resources */
  override def close(): Future[Unit] = FutureUnit

  // Build the http/1.x pipeline, including ssl if necessary
  // TODO: this would be useful for a pooled SessionPool as well.
  private def connectPipeline(
      url: UrlComposition,
      head: HeadStage[ByteBuffer]): HttpClientSession = {
    val clientStage = new Http1ClientStage(config)
    var builder = LeafBuilder(clientStage)
    if (url.isTls)
      builder = builder.prepend(new SSLStage(config.getClientSslEngine()))
    builder.base(head)
    head.sendInboundCommand(Command.Connected)
    clientStage
  }
}
