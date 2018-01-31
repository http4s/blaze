package org.http4s.blaze.http

import org.http4s.blaze.channel.nio2.ClientChannelFactory
import org.http4s.blaze.http.ClientSessionManagerImpl._
import org.http4s.blaze.http.HttpClientSession.{Closed, ReleaseableResponse, Status}
import org.http4s.blaze.http.http1.client.Http1ClientStage
import org.http4s.blaze.http.http2.client.ClientSelector
import org.http4s.blaze.http.util.UrlTools.UrlComposition
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.http4s.blaze.util.Execution
import org.log4s.getLogger

import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

private object ClientSessionManagerImpl {
  case class ConnectionId(scheme: String, authority: String)

  case class Http1SessionProxy(id: ConnectionId, parent: Http1ClientSession) extends Http1ClientSession {
    override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = parent.dispatch(request)
    override def status: Status = parent.status
    override def close(within: Duration): Future[Unit] = parent.close(within)
  }

  def apply(config: HttpClientConfig): ClientSessionManagerImpl = {
    val sessionCache = new java.util.HashMap[ConnectionId, java.util.Collection[HttpClientSession]]
    new ClientSessionManagerImpl(sessionCache, config)
  }
}

private final class ClientSessionManagerImpl(
    sessionCache: java.util.Map[ConnectionId, java.util.Collection[HttpClientSession]],
    config: HttpClientConfig
  ) extends ClientSessionManager {

  private[this] def connectionId(composition: UrlComposition): ConnectionId =
    ConnectionId(composition.scheme, composition.authority)

  private[this] implicit def ec = Execution.trampoline

  private[this] val logger = getLogger
  private[this] val socketFactory = new ClientChannelFactory(group = config.channelGroup)
  private[this] val http2ClientSelector = new ClientSelector(config)

  override def acquireSession(request: HttpRequest): Future[HttpClientSession] = {
    logger.debug(s"Acquiring session for request $request")
     UrlComposition(request.url) match {
      case Success(urlComposition) =>
        val id = connectionId(urlComposition)
        findExistingSession(id) match {
          case Some(session) =>
            logger.debug(s"Found hot session for id $id: $session")
            Future.successful(session)

          case None =>
            logger.debug(s"No suitable session found for id $id. Creating new session.")
            createNewSession(urlComposition, id)
        }

      case Failure(t) => Future.failed(t)
    }
  }

  private[this] def findExistingSession(id: ConnectionId): Option[HttpClientSession] = sessionCache.synchronized {
    sessionCache.get(id) match {
      case null => None // nop
      case sessions =>
        var session: Option[HttpClientSession] = None
        val it = sessions.iterator
        while(session.isEmpty && it.hasNext) it.next() match {
          case h2: Http2ClientSession if h2.status == Closed =>
            discardSession(h2) // make sure its closed
            it.remove()

          case h2: Http2ClientSession if 0.1 < h2.quality =>
            session = Some(h2)

          case _: Http2ClientSession => () // nop

          case h1: Http1ClientSession =>
            it.remove()
            if (h1.status != Closed) { // Should never be busy
              session = Some(h1) // found a suitable HTTP/1.x session.
            }
        }

        // if we took the last session, drop the collection
        if (sessions.isEmpty) {
          sessionCache.remove(id)
        }

        session
    }
  }

  private[this] def createNewSession(urlComposition: UrlComposition, id: ConnectionId): Future[HttpClientSession] = {
    logger.debug(s"Creating new session for id $id")
    val p = Promise[HttpClientSession]

    socketFactory.connect(urlComposition.getAddress).onComplete {
      case Failure(e) => p.tryFailure(e)
      case Success(head) =>
        if (urlComposition.scheme.equalsIgnoreCase("https")) {
          val engine = config.getClientSslEngine()
          engine.setUseClientMode(true)

          val rawSession = Promise[HttpClientSession]

          LeafBuilder(http2ClientSelector.newStage(engine, rawSession))
            .prepend(new SSLStage(engine))
            .base(head)
          head.sendInboundCommand(Command.Connected)

          p.completeWith(rawSession.future.map {
            case h1: Http1ClientSession =>
              new Http1SessionProxy(id, h1)

            case h2: Http2ClientSession =>
              addSessionToCache(id, h2)
              h2
          })

        } else {
          val clientStage = new Http1ClientStage(config)
          val builder = LeafBuilder(clientStage)
          builder.base(head)
          head.sendInboundCommand(Command.Connected)
          p.trySuccess(new Http1SessionProxy(id, clientStage))
        }
    }

    p.future
  }

  override def returnSession(session: HttpClientSession): Unit = {
    logger.debug(s"Returning session $session")
    session match {
      case _ if session.isClosed => () // nop
      case _: Http2ClientSession => () // nop
      case h1: Http1ClientSession if !h1.isReady =>
        logger.debug(s"Closing unready session $h1")
        discardSession(h1)

      case proxy: Http1SessionProxy => addSessionToCache(proxy.id, proxy)
      case other => sys.error(s"The impossible happened! Found invalid type: $other")
    }
  }

  override def close(): Future[Unit] = {
    logger.debug(s"Closing ${this.getClass.getSimpleName}")
    sessionCache.synchronized {
      sessionCache.asScala.values.foreach(_.asScala.foreach(discardSession(_)))
      sessionCache.clear()
    }
    Future.successful(())
  }

  private[this] def discardSession(session: HttpClientSession): Unit = {
    try session.closeNow().onComplete {
      case Failure(t) => logger.info(t)(s"Failure closing session $session")
      case _ => ()
    } catch { case NonFatal(t) =>
      logger.warn(t)("Exception caught while closing session")
    }
  }

  private[this] def addSessionToCache(id: ConnectionId, session: HttpClientSession): Unit = {
    val size = sessionCache.synchronized {
      val collection = sessionCache.get(id) match {
        case null =>
          val stack = new java.util.Stack[HttpClientSession]()
          sessionCache.put(id, stack)
          stack
        case some => some
      }

      collection.add(session)
      collection.size
    }
    logger.debug(s"Added session $session. Now ${size} sessions for id $id")
  }
}
