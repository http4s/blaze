/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.http.http2.client

import org.http4s.blaze.http.HttpClientSession.{Closed, Ready, ReleaseableResponse, Status}
import org.http4s.blaze.http.http2.Http2Settings
import org.http4s.blaze.http.util.UrlTools.UrlComposition
import org.http4s.blaze.http.{Http2ClientSession, _}
import org.http4s.blaze.util.FutureUnit
import org.specs2.mutable.Specification

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

class Http2ClientSessionManagerImplSpec extends Specification {
  private val connectionId = ClientSessionManagerImpl.ConnectionId("https", "www.foo.com")

  private val req =
    HttpRequest("GET", "https://www.foo.com/bar", 1, 1, Seq.empty, BodyReader.EmptyBodyReader)

  private def cacheWithSession(
      session: Http2ClientSession): mutable.Map[String, Future[Http2ClientSession]] = {
    val map = new mutable.HashMap[String, Future[Http2ClientSession]]()
    map.put(connectionId.authority, Future.successful(session))

    map
  }

  private def managerWithCache(
      cache: mutable.Map[String, Future[Http2ClientSession]]): Http2ClientSessionManagerImpl =
    new Http2ClientSessionManagerImpl(HttpClientConfig.Default, Http2Settings.default, cache)

  private def managerWithSession(session: Http2ClientSession): Http2ClientSessionManagerImpl =
    managerWithCache(cacheWithSession(session))

  "Http2ClientSessionManagerImpl" should {
    "acquire an existing session from the pool" in {
      val session = new Http2ClientSession {
        override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = ???
        override def close(within: Duration): Future[Unit] = ???
        override def status: Status = Ready
        override def quality: Double = 1.0
        override def ping(): Future[Duration] = ???
      }

      val manager = managerWithSession(session)
      manager.acquireSession(req).value must beSome(Success(session))
    }

    "get the same session from the pool so long as it's healthy" in {
      val session = new Http2ClientSession {
        override def dispatch(request: HttpRequest): Future[ReleaseableResponse] = ???
        override def close(within: Duration): Future[Unit] = ???
        override def status: Status = Ready
        override def quality: Double = 1.0
        override def ping(): Future[Duration] = ???
      }
      val cache = cacheWithSession(session)
      val manager = managerWithCache(cache)

      val f1 = manager.acquireSession(req)
      val f2 = manager.acquireSession(req)

      f1.value must_== f2.value
      f1.value must beSome(Success(session))
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

      val cache = cacheWithSession(bad)
      val markerEx = new Exception("marker")
      val manager =
        new Http2ClientSessionManagerImpl(HttpClientConfig.Default, Http2Settings.default, cache) {
          override protected def acquireSession(url: UrlComposition): Future[Http2ClientSession] =
            Future.failed(markerEx)
        }

      // if we get the marker it's because we tried to make a new session
      manager.acquireSession(req).value must beLike { case Some(Failure(ex)) =>
        ex must_== markerEx
      }

      bad.closed must beTrue
    }

    "reject http urls" in {
      val manager = managerWithSession(null) // not not important
      val fSession = manager.acquireSession(req.copy(url = "http://www.foo.com/bar"))
      fSession.value must beLike { case Some(Failure(_: IllegalArgumentException)) =>
        ok
      }
    }
  }
}
