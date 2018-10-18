package org.http4s.blaze.http.http2.client

import java.nio.ByteBuffer

import org.http4s.blaze.channel.nio2.ClientChannelFactory
import org.http4s.blaze.http.ALPNTokens._
import org.http4s.blaze.http.HttpClientSession.Ready
import org.http4s.blaze.http._
import org.http4s.blaze.http.util.UrlTools.UrlComposition
import org.http4s.blaze.http.http2._
import org.http4s.blaze.http.util.UrlTools
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.pipeline.{Command, HeadStage, LeafBuilder}
import org.http4s.blaze.util.{Execution, FutureUnit}
import org.log4s.getLogger

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object Http2ClientSessionManagerImpl {
  def apply(
      config: HttpClientConfig,
      initialSettings: ImmutableHttp2Settings
  ): Http2ClientSessionManagerImpl =
    new Http2ClientSessionManagerImpl(
      config = config,
      initialSettings = initialSettings,
      sessionCache = new mutable.HashMap[String, Future[Http2ClientSession]])
}

private[http] class Http2ClientSessionManagerImpl(
    config: HttpClientConfig,
    initialSettings: ImmutableHttp2Settings,
    sessionCache: mutable.Map[String, Future[Http2ClientSession]])
    extends ClientSessionManager {

  private[this] val logger = getLogger
  private[this] val factory = new ClientChannelFactory(group = config.channelGroup)

  override def acquireSession(request: HttpRequest): Future[HttpClientSession] =
    UrlTools.UrlComposition(request.url) match {
      case Failure(t) =>
        Future.failed(t)

      case Success(composition) if !composition.scheme.equalsIgnoreCase("https") =>
        val ex = new IllegalArgumentException(s"Only https URL's allowed")
        Future.failed(ex)

      case Success(composition) =>
        sessionCache.synchronized {
          sessionCache.get(composition.authority) match {
            case Some(session) =>
              session
                .flatMap { s =>
                  if (s.status == Ready) session
                  else makeAndStoreSession(composition)
                }(Execution.trampoline)
                .recoverWith {
                  case err =>
                    logger.info(err)(s"Found bad session. Replacing.")
                    makeAndStoreSession(composition)
                }(Execution.trampoline)

            case None =>
              makeAndStoreSession(composition)
          }
        }
    }

  /** Close the `SessionManager` and free any resources */
  override def close(): Future[Unit] = {
    val sessions = sessionCache.synchronized {
      val sessions = sessionCache.values.toList
      sessionCache.clear()
      sessions
    }

    sessions.foldLeft(FutureUnit) { (acc, s) =>
      // we turn it into a Future outside of the Future.flatMap
      // to ensure that closeNow is called on each session
      val f = s.flatMap(_.closeNow())(Execution.directec)
      acc.flatMap(_ => f)(Execution.trampoline)
    }
  }

  // No need to do anything on return since HTTP/2 sessions are shared, not taken.
  override def returnSession(session: HttpClientSession): Unit = ()

  // starts to acquire a new session and adds the attempt to the sessionCache
  private[this] def makeAndStoreSession(url: UrlComposition): Future[Http2ClientSession] = {
    logger.debug(s"Creating a new session for composition $url")

    val fSession = acquireSession(url)
    sessionCache.put(url.authority, fSession) match {
      case Some(old) =>
        old.onComplete {
          case Success(session) => session.close(Duration.Inf)
          case Failure(_) => // nop
        }(Execution.directec)

      case None => // nop
    }
    fSession
  }

  protected def acquireSession(url: UrlComposition): Future[Http2ClientSession] = {
    logger.debug(s"Creating a new session for composition $url")
    factory.connect(url.getAddress).flatMap(initialPipeline)(Execution.directec)
  }

  protected def initialPipeline(head: HeadStage[ByteBuffer]): Future[Http2ClientSession] = {
    val p = Promise[Http2ClientSession]

    def buildConnection(s: String): LeafBuilder[ByteBuffer] = {
      if (s != H2 && s != H2_14) {
        logger.error(s"Failed to negotiate H2. Using H2 protocol anyway.")
      }

      val f = new DefaultFlowStrategy(initialSettings)
      val handShaker = new ClientPriorKnowledgeHandshaker(initialSettings, f, Execution.trampoline)
      p.completeWith(handShaker.clientSession)
      LeafBuilder(handShaker)
    }

    val engine = config.getClientSslEngine()
    engine.setUseClientMode(true)

    LeafBuilder(new ALPNClientSelector(engine, Seq(H2, H2_14), H2, buildConnection))
      .prepend(new SSLStage(engine))
      .base(head)

    head.sendInboundCommand(Command.Connected)
    p.future
  }
}
