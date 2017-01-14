package org.http4s.blaze.servertestsuite

import java.util.concurrent.TimeUnit

import org.asynchttpclient.{Dsl, Request, RequestBuilder, Response}

import scala.concurrent.duration.Duration

/** Basic scaffold for running http requests against a server
  * @param timeout timeout for request. Must be finite and greater than 0
  */
class HttpClient(host: String, port: Int, timeout: Duration) {
  require(timeout.isFinite && timeout.length > 0)

  private val client = Dsl.asyncHttpClient(
    Dsl.config()
      .setMaxConnections(500)
      .setMaxConnectionsPerHost(200)
      .setPooledConnectionIdleTimeout(100)
      .setConnectionTtl(500)
  )

  // Expects the route to echo it back
  def withArbitraryRequest() = {
    import org.scalacheck._
    import Prop.forAll

    forAll(GeneralInstances.genRequest(host, port)) { request =>
      val response = runRequest(request)
      request.getHeaders == response.getHeaders() &&
      response.getResponseBodyAsBytes == request.getByteData
    }
  }

  // Not afraid to block: this is for testing.
  def runGet(uri: String): Response = {
    val request =
      new RequestBuilder()
        .setUrl(s"http://$host:$port$uri")
        .setMethod("GET")
        .build

    runRequest(request)
  }

  // Not afraid to block: this is for testing.
  def runRequest(request: Request): Response = {
    client.prepareRequest(request)
      .execute()
      .toCompletableFuture
      .get(timeout.toMillis, TimeUnit.MILLISECONDS)
  }

  def close(): Unit = {
    client.close()
  }
}
