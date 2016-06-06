package org.http4s.blaze.http

import org.specs2.mutable._

import scala.concurrent.Await
import scala.concurrent.duration._

import scala.concurrent.ExecutionContext.Implicits.global


class HttpClientSpec extends Specification {

  "HttpClient" should {

    "Make https requests" in {
      val f = HttpClient.GET("https://github.com/"){ r => r.stringBody().map((_, r.code)) }

      val (body, code) = Await.result(f, 10.seconds)

      println(s"Body: $body")
      code should_== 200
    }
  }

}
