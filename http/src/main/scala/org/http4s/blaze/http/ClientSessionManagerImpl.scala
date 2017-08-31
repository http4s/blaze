package org.http4s.blaze.http

import org.http4s.blaze.channel.nio2.ClientChannelFactory
import org.http4s.blaze.http.ClientSessionManagerImpl._
import org.http4s.blaze.http.HttpClientSession.{Closed, ReleaseableResponse, Status}
import org.http4s.blaze.http.http1.client.Http1ClientStage
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

private final class ClientSessionManagerImpl(sessionCache: java.util.Map[ConnectionId, java.util.Collection[HttpClientSession]], config: HttpClientConfig) extends ClientSessionManager {

  private[this] def getId(composition: UrlComposition): ConnectionId =
    ConnectionId(composition.scheme, composition.authority)

  private[this] implicit def ec = Execution.trampoline

  private[this] val logger = getLogger
  private[this] val socketFactory = new ClientChannelFactory(group = config.channelGroup)

  override def acquireSession(request: HttpRequest): Future[HttpClientSession] = {
    logger.debug(s"Acquiring session for request $request")
     UrlComposition(request.url) match {
      case Success(urlComposition) =>
        val id = getId(urlComposition)
        val session = findExistingSession(id)

        if (session == null) createNewSession(urlComposition, id)
        else {
          logger.debug(s"Found hot session for id $id: $session")
          Future.successful(session)
        }

      case Failure(t) => Future.failed(t)
    }
  }

  // WARNING: can emit `null`. For internal use only
  private[this] def findExistingSession(id: ConnectionId): HttpClientSession = sessionCache.synchronized {
    sessionCache.get(id) match {
      case null => null // nop
      case sessions =>
        var session: HttpClientSession = null
        val it = sessions.iterator
        while(session == null && it.hasNext) it.next() match {
          case h2: Http2ClientSession if h2.status == Closed =>
            h2.closeNow() // make sure its closed
            it.remove()

          case h2: Http2ClientSession if h2.quality > 0.1 =>
            session = h2

          case _: Http2ClientSession => () // nop

          case h1: Http1ClientSession =>
            it.remove()
            if (h1.status != Closed) { // Should never be busy
              session = h1 // found a suitable HTTP/1.x session.
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
        val clientStage = new Http1ClientStage(config)
        var builder = LeafBuilder(clientStage)
        if (urlComposition.scheme.equalsIgnoreCase("https")) {
          val engine = config.getClientSslEngine()
          engine.setUseClientMode(true)
          builder = builder.prepend(new SSLStage(engine))
        }

        builder.base(head)
        head.sendInboundCommand(Command.Connected)
        p.trySuccess(new Http1SessionProxy(id, clientStage))
      }

    p.future
  }

  /** Return the session to the pool.
    *
    * Depending on the state of the session and the nature of the pool, this may
    * either cache the session for future use or close it.
    */
  override def returnSession(session: HttpClientSession): Unit = {
    logger.debug(s"Returning session $session")
    session match {
      case _ if session.isClosed => () // nop
      case _: Http2ClientSession => () // nop
      case h1: Http1ClientSession if !h1.isReady =>
        logger.debug(s"Closing unready session $h1")
        h1.closeNow() // we just orphan the Future. Don't care.
        ()

      case proxy: Http1SessionProxy => addSessionToCache(proxy.id, proxy)
      case other => sys.error(s"The impossible happened! Found invalid type: $other")
    }
  }

  /** Close the `SessionManager` and free any resources */
  override def close(): Future[Unit] = {
    logger.debug(s"Closing session")
    sessionCache.synchronized {
      sessionCache.asScala.values.foreach(_.asScala.foreach { session =>
        try session.closeNow()
        catch { case NonFatal(t) =>
          logger.warn(t)("Exception caught while closing session")
        }
      })
      sessionCache.clear()
    }
    Future.successful(())
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
