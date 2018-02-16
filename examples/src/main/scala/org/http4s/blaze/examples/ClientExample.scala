package org.http4s.blaze.examples

import org.http4s.blaze.http.{ClientResponse, HttpClient}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object ClientExample {

  lazy val client = HttpClient.pooledHttpClient

  val requests = Seq(
    "https://www.google.com",
    "https://github.com",
    "http://http4s.org",
    "https://www.google.com/search?client=ubuntu&channel=fs&q=fish&ie=utf-8&oe=utf-8"
  )

  def main(args: Array[String]): Unit = {

    val fs = (requests ++ requests).map { url =>
      val r = client.GET(url) { response =>
        println(s"Status: ${response.status}")
        ClientResponse.stringBody(response)
      }

      Thread.sleep(500) // give the h2 sessions time to materialize
      r
    }

    val bodies = Await.result(Future.sequence(fs), 5.seconds)
    println(s"Read ${bodies.foldLeft(0)(_ + _.length)} bytes from ${bodies.length} requests")
  }
}
