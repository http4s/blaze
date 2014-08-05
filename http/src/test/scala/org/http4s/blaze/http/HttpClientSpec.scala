package org.http4s.blaze.http

import org.specs2.mutable._

import scala.concurrent.Await
import scala.concurrent.duration._
import org.specs2.time.NoTimeConversions

/**
 * Created by Bryce Anderson on 3/16/14.
 */
class HttpClientSpec extends Specification with NoTimeConversions {

  "HttpClient" should {

    "Make https requests" in {
      val f = HttpClient.GET("https://github.com/")

      val r = Await.result(f, 5.seconds)

      r.code should_== 200
    }
  }

}
