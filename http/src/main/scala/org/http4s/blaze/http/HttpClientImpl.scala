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

package org.http4s.blaze.http

import org.http4s.blaze.http.HttpClientSession.ReleaseableResponse
import org.http4s.blaze.util.Execution

import scala.concurrent.Future
import scala.util.Failure

private[http] class HttpClientImpl(sessionPool: ClientSessionManager) extends HttpClient {

  /** Release underlying resources associated with the `HttpClient` */
  override def close(): Future[Unit] = sessionPool.close()

  private implicit def ec = Execution.directec

  private class ReleaseableResponseProxy(session: HttpClientSession, resp: ReleaseableResponse)
      extends ClientResponse(resp.code, resp.status, resp.headers, resp.body)
      with ReleaseableResponse {
    private[this] var released = false
    override def release(): Unit =
      synchronized {
        if (!released) {
          released = true
          resp.release()
          sessionPool.returnSession(session)
        }
      }
  }

  override def unsafeDispatch(request: HttpRequest): Future[ReleaseableResponse] =
    sessionPool.acquireSession(request).flatMap { session =>
      val f =
        session.dispatch(request).map(new ReleaseableResponseProxy(session, _))

      // If we fail to acquire the response, it is our job to return the
      // session since the user has no handle to do so.
      f.onComplete {
        case Failure(_) => sessionPool.returnSession(session)
        case _ => ()
      }

      f
    }
}
