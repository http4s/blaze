/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http

import org.http4s.blaze.http.HttpClientSession.ReleaseableResponse
import org.http4s.blaze.http.http1.client.BasicHttp1ClientSessionManager
import org.http4s.blaze.util.Execution
import scala.concurrent.Future

/** Generic interface for making HTTP client requests
  *
  * A [[HttpClient]] hides the details of the underlying sockets, connection
  * pools etc. For a representation of a concrete session implementation see
  * [[HttpClientSession]].
  */
trait HttpClient extends ClientActions {

  /** Release underlying resources associated with the [[HttpClient]]
    *
    * The resultant `Future[Unit]` will resolve once the resources associated
    * with the client have been freed.
    */
  def close(): Future[Unit]

  /** Dispatch a request, resulting in the response
    *
    * @param request request to dispatch
    * @return the response. The cleanup of the resources associated with
    *         this dispatch are tied to the [[BodyReader]] of the [[ClientResponse]].
    *         Release of resources is triggered by complete consumption of the `MessageBody`
    *         or by calling `MessageBody.discard()`, whichever comes first.
    */
  def unsafeDispatch(request: HttpRequest): Future[ReleaseableResponse]

  /** Safely dispatch a client request
    *
    * Resources associated with this dispatch are guarenteed to be cleaned up during the
    * resolution of the returned `Future[T]`, regardless of if it is successful or not.
    *
    * @note The resources _may_ be cleaned up before the future resolves, but this is
    *       dependant on both the implementation and if the resources have been fully
    *       consumed.
    */
  def apply[T](request: HttpRequest)(f: ClientResponse => Future[T]): Future[T] =
    unsafeDispatch(request).flatMap { resp =>
      val result = f(resp)
      result.onComplete(_ => resp.release())(Execution.directec)
      result
    }(Execution.directec)
}

object HttpClient {

  /** Basic implementation of a HTTP/1.1 client.
    *
    * This client doesn't do any session pooling, so one request = one socket connection.
    */
  lazy val basicHttp1Client: HttpClient = {
    val pool = new BasicHttp1ClientSessionManager(HttpClientConfig.Default)
    new HttpClientImpl(pool)
  }

  lazy val pooledHttpClient: HttpClient = {
    val pool = ClientSessionManagerImpl(HttpClientConfig.Default)
    new HttpClientImpl(pool)
  }
}
