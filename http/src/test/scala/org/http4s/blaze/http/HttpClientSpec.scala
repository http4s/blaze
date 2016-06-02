//package org.http4s.blaze.http
//
//import org.specs2.mutable._
//
//import scala.concurrent.Await
//import scala.concurrent.duration._
//
//
//class HttpClientSpec extends Specification {
//
//  "HttpClient" should {
//
//    "Make https requests" in {
//      val f = HttpClient.GET("https://github.com/")
//
//      val r = Await.result(f, 10.seconds)
//
//      println(r.stringBody())
//      r.code should_== 200
//    }
//  }
//
//}
