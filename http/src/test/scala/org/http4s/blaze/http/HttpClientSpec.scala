package org.http4s.blaze.http

import org.scalatest.{Matchers, WordSpec}
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Created by Bryce Anderson on 3/16/14.
 */
class HttpClientSpec extends WordSpec with Matchers {

  "HttpClient" should {

    "Make https requests" in {
      val f = HttpClient.GET("https://www.google.com/")

      val r = Await.result(f, 5.seconds)

      r.code should equal(200)
    }
  }

}
