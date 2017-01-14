package org.http4s.blaze.http.endtoend

import java.nio.charset.StandardCharsets

import org.asynchttpclient.RequestBuilder
import org.http4s.blaze.http.{HttpService, RouteAction}
import org.http4s.blaze.servertestsuite.HttpClient
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class ServerSpec extends Specification {

  implicit def ec = ExecutionContext.global

  val helloWorld = "Hello, world!"

  val service: HttpService = { request =>
    request.uri match {
      case "/hello" => Future.successful(RouteAction.Ok(helloWorld))
      case "/headers" => Future.successful {
        val str = request.headers
          .filter(_._1.startsWith("special"))
          .foldLeft("") { case (acc, (k, v)) => acc + s"$k: $v\n" }

        RouteAction.Ok(str)
      }

      case "/streaming" => Future.successful {
        var remaining = 1000
        RouteAction.Streaming(200, "OK", Nil) {
          if (remaining == 0) Future.successful(BufferTools.emptyBuffer)
          else {
            remaining -= 1
            Future.successful(StandardCharsets.UTF_8.encode(s"remaining: $remaining\n"))
          }
        }
      }

      case "/post" if request.method == "POST" => request.body.accumulate().map { bytes =>
        val body = StandardCharsets.UTF_8.decode(bytes).toString
        RouteAction.Ok(s"You posted: $body")
      }

      case "/echo" if request.method == "POST" => Future.successful {
        RouteAction.Streaming(200, "OK", Nil)(request.body())
      }

      case url => Future.successful(RouteAction.Ok(s"url: $url"))
    }
  }

  val server = LocalServer(0)(service)
  val client = new HttpClient("localhost", server.getAddress.getPort, 10.seconds)

  def makeUrl(url: String): String = s"http://localhost:${server.getAddress.getPort}$url"

  "blaze server" should {
    "do a hello world request" in {
      val resp = client.runGet("/hello")
      resp.getResponseBody(StandardCharsets.UTF_8) must_== helloWorld
      resp.getHeaders.get("content-length") must_== helloWorld.length.toString
    }

    "echo headers" in {
      val req = new RequestBuilder()
        .addHeader("special1", "val1")
        .addHeader("special2", "val2")
        .setUrl(makeUrl("/headers"))
        .setMethod("GET")
        .build()
      val resp = client.runRequest(req)

      resp.getResponseBody(StandardCharsets.UTF_8) must_== "special1: val1\nspecial2: val2\n"
    }

    "stream a response body" in {
      val resp = client.runGet("/streaming")
      resp.getResponseBody() must_== (0 until 1000).foldRight(""){ (remaining, acc) => acc + s"remaining: $remaining\n" }
    }

    "post a body" in {
      val req = new RequestBuilder()
        .setUrl(makeUrl("/post"))
        .setMethod("POST")
        .setBody("this is the body")
        .build()

      val resp = client.runRequest(req)
      resp.getResponseBody() must_== "You posted: this is the body"
    }
  }

  // Need to clean up after the spec is complete
  step {
    server.close()
    client.close()
  }
}
