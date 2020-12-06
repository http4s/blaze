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
