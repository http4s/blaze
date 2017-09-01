package org.http4s.blaze.http.http2.client

import java.nio.ByteBuffer

import org.http4s.blaze.channel.nio2.ClientChannelFactory
import org.http4s.blaze.http.ALPNTokens._
import org.http4s.blaze.http._
import org.http4s.blaze.http.util.UrlTools.UrlComposition
import org.http4s.blaze.http.http2.Http2Connection.Running
import org.http4s.blaze.http.http2._
import org.http4s.blaze.http.util.UrlTools
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.pipeline.{Command, HeadStage, LeafBuilder}
import org.http4s.blaze.util.Execution
import org.log4s.getLogger

import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

private[http] class Http2ClientSessionManagerImpl(
    config: HttpClientConfig,
    initialSettings: ImmutableHttp2Settings)
  extends ClientSessionManager {

  private[this] val logger = getLogger
  private[this] val factory = new ClientChannelFactory(group = config.channelGroup)
  private[this] val sessionCache = new mutable.HashMap[String, Future[Http2ClientConnection]]

  override def acquireSession(request: HttpRequest): Future[HttpClientSession] =
    UrlTools.UrlComposition(request.url) match {
      case Failure(t) => Future.failed(t)
      case Success(composition) =>
        sessionCache.synchronized {
          sessionCache.get(composition.authority) match {
            case Some(session) =>
              session.value match {
                case Some(Success(s)) if s.state == Running => session
                // we have either a session that is no good any more, or never was.
                case _ => makeAndStoreSession(composition)
              }

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

    sessions.foldLeft(Future.successful(())){ (acc, s) =>
      // we turn it into a Future outside of the Future.flatMap
      // to ensure that closeNow is called on each session
      val f = s.flatMap(_.closeNow())(Execution.directec)
      acc.flatMap(_ => f)(Execution.trampoline)
    }
  }

  /** Return the session to the pool.
    *
    * Depending on the state of the session and the nature of the pool, this may
    * either cache the session for future use or close it.
    */
  override def returnSession(session: HttpClientSession): Unit = ()

  // starts to acquire a new session and adds the attempt to the sessionCache
  private def makeAndStoreSession(url: UrlComposition): Future[Http2ClientConnection] = {
    logger.debug(s"Creating a new session for composition $url")

    val fSession = acquireSession(url)
    sessionCache.put(url.authority, fSession)
    fSession
  }

  private[this] def acquireSession(url: UrlComposition): Future[Http2ClientConnection] = {
    logger.debug(s"Creating a new session for composition $url")
    factory.connect(url.getAddress).flatMap(initialPipeline)(Execution.directec)
  }

  // protected for testing purposes
  protected def initialPipeline(head: HeadStage[ByteBuffer]): Future[Http2ClientConnection] = {
    val p = Promise[Http2ClientConnection]

    def buildConnection(s: String): LeafBuilder[ByteBuffer] = {
      if (s != H2 && s != H2_14)
        logger.error(s"Failed to negotiate H2. Using H2 protocol anyway.")

      val f = new DefaultFlowStrategy(initialSettings)
      val handShaker = new Http2TlsClientHandshaker(initialSettings, f, Execution.trampoline)
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
