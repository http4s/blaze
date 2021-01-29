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

package org.http4s.blaze.http.endtoend.scaffolds

import java.util.concurrent.TimeUnit

import org.asynchttpclient.{Dsl, Request, RequestBuilder, Response}
import org.http4s.blaze.http.{HttpRequest, HttpResponsePrelude}
import org.http4s.blaze.internal.compat.CollectionConverters._
import scala.concurrent.Await
import scala.concurrent.duration.Duration

/** `ClientScaffold` implementation using the `async-http-client`
  *
  * @param timeout timeout for request. Must be finite and greater than 0
  */
private[endtoend] final class AsyncHttp1ClientScaffold(timeout: Duration)
    extends ClientScaffold(1, 1) {
  require(timeout.isFinite && timeout.length > 0)

  private val client = Dsl.asyncHttpClient(
    Dsl
      .config()
      .setMaxConnections(500)
      .setMaxConnectionsPerHost(200)
      .setPooledConnectionIdleTimeout(100)
      .setConnectionTtl(500)
  )

  override def runRequest(request: HttpRequest): ClientScaffold.Response = {
    val asyncHttpRequest = requestToAsyncHttpRequest(request)
    val response = runAsyncHttpRequest(asyncHttpRequest)
    asyncHttpResponseToResponse(response)
  }

  private[this] def requestToAsyncHttpRequest(request: HttpRequest): Request = {
    val body = Await.result(request.body.accumulate(), timeout)

    val requestBuilder = new RequestBuilder()
    requestBuilder.setUrl(request.url)
    requestBuilder.setMethod(request.method)
    request.headers.foreach { case (k, v) =>
      requestBuilder.setHeader(k, v)
    }

    requestBuilder.setBody(body)
    requestBuilder.build()
  }

  private[this] def asyncHttpResponseToResponse(resp: Response): ClientScaffold.Response = {
    val statusCode = resp.getStatusCode
    val status = resp.getStatusText
    val hs = resp.getHeaders.entries().asScala.map(e => e.getKey -> e.getValue)
    val response = HttpResponsePrelude(statusCode, status, hs)

    ClientScaffold.Response(response, resp.getResponseBodyAsBytes)
  }

  // Not afraid to block: this is for testing.
  private[this] def runAsyncHttpRequest(request: Request): Response =
    client
      .prepareRequest(request)
      .execute()
      .toCompletableFuture
      .get(timeout.toMillis, TimeUnit.MILLISECONDS)

  def close(): Unit =
    client.close()
}
