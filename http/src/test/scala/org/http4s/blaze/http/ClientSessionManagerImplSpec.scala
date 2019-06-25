package org.http4s.blaze.http

import org.http4s.blaze.http.HttpClientSession.{Closed, Ready, ReleaseableResponse, Status}
import org.http4s.blaze.util.FutureUnit
import org.specs2.mutable.Specification

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Success

class ClientSessionManagerImplSpec extends Specification {

  private val connectionId = ClientSessionManagerImpl.ConnectionId("http", "www.foo.com")

  private val req =
    HttpRequest("GET", "http://www.foo.com/bar", 1, 1, Seq.empty, BodyReader.EmptyBodyReader)

  private def cacheWithSessions(sessions: HttpClientSession*): java.util.Map[
    ClientSessionManagerImpl.ConnectionId,
    java.util.Collection[HttpClientSession]] = {
    val map = new java.util.HashMap[
      ClientSessionManagerImpl.ConnectionId,
      java.util.Collection[HttpClientSession]]()
    val coll = new java.util.LinkedList[HttpClientSession]()
    sessions.foreach(coll.add(_))
    map.put(connectionId, coll)
    map
  }

  private def managerWithSessions(sessions: HttpClientSession*): ClientSessionManagerImpl = {
    val cache = cacheWithSessions(sessions: _*)
    new ClientSessionManagerImpl(cache, HttpClientConfig.Default)
  }

  "ClientSessionManagerImpl" should {
    "acquire an existing session from the pool" in {
      val session = new Http1ClientSession {
        override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = ???
        override def close(within: Duration): Future[Unit] = ???
        override def status: Status = Ready
      }

      val manager = managerWithSessions(session)
      manager.acquireSession(req).value must beSome(Success(session))
    }

    "add a good session back to the pool" in {
      val session = new Http1ClientSession {
        override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = ???
        override def close(within: Duration): Future[Unit] = ???
        override def status: Status = Ready
      }
      val cache = new java.util.HashMap[
        ClientSessionManagerImpl.ConnectionId,
        java.util.Collection[HttpClientSession]]()
      val manager = new ClientSessionManagerImpl(cache, HttpClientConfig.Default)

      val proxiedSession = ClientSessionManagerImpl.Http1SessionProxy(connectionId, session)

      manager.returnSession(proxiedSession)
      cache.get(connectionId).iterator.next must_== proxiedSession
    }

    "dead HTTP/2 sessions are removed" in {
      object bad extends Http2ClientSession {
        var closed = false
        override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = ???
        override def close(within: Duration): Future[Unit] = {
          closed = true
          FutureUnit
        }
        override def ping(): Future[Duration] = ???
        override def status: Status = Closed
        override def quality: Double = 1.0
      }

      object lowQuality extends Http2ClientSession {
        override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = ???
        override def close(within: Duration): Future[Unit] = ???
        override def ping(): Future[Duration] = ???
        override def status: Status = Ready
        override def quality: Double = 0.0
      }

      object good extends Http2ClientSession {
        override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = ???
        override def close(within: Duration): Future[Unit] = ???
        override def ping(): Future[Duration] = ???
        override def status: Status = Ready
        override def quality: Double = 1.0
      }

      val manager = managerWithSessions(bad, lowQuality, good)
      manager.acquireSession(req).value must beSome(Success(good))
      bad.closed must beTrue
    }
  }
}
