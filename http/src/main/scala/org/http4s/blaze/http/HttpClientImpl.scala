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
    override def release(): Unit = synchronized {
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
