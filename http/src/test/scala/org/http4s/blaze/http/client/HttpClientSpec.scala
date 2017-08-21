package org.http4s.blaze.http.client

import org.http4s.blaze.http.{ClientResponse, HttpClient}
import org.specs2.mutable._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._


class HttpClientSpec extends Specification {

  val client = HttpClient.basicHttp1Client

  // TODO: we shouldn't be calling out to external resources for tests
  "HttpClient" should {
    "Make https requests" in {
      val path = "https://github.com/"
      val hs = Seq("host" -> "github.com")

      val f = client.GET(path, hs){ r => ClientResponse.stringBody(r).map((_, r.code)) }
      val (body, code) = Await.result(f, 10.seconds)
      println(s"Body: $body")
      code must_== 200
    }
  }
}
