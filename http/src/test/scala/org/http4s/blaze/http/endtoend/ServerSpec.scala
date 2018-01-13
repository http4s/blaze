package org.http4s.blaze.http.endtoend

import java.nio.charset.StandardCharsets

import org.http4s.blaze.http._
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

import scala.concurrent.{Await, Awaitable, ExecutionContext, Future}
import scala.concurrent.duration._

class ServerSpec extends Specification {

  implicit def ec = ExecutionContext.global

  def await[T](t: Awaitable[T]): T = Await.result(t, 5.seconds)

  val helloWorld = "Hello, world!"

  val service: HttpService = { request =>
    request.url match {
      case "/hello" => Future.successful(RouteAction.Ok(helloWorld))
      case "/headers" => Future.successful {
        val str = request.headers
          .filter(_._1.startsWith("special"))
          .foldLeft("") { case (acc, (k, v)) => acc + s"$k: $v\n" }

        RouteAction.Ok(str)
      }

      case "/streaming" => Future.successful {
        @volatile var remaining = 1000
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
  val client = HttpClient.basicHttp1Client

  def makeUrl(url: String): String = s"http://localhost:${server.getAddress.getPort}$url"

  "blaze server" should {
    "do a hello world request" in {
      val (body, len) = await {
        client.GET(makeUrl("/hello")){ resp =>
          ClientResponse.stringBody(resp).map { body =>
            val len = resp.headers.collectFirst {
              case (k, v) if k.equalsIgnoreCase("content-length") => v
            }

            body -> len
          }
        }
      }
      body must_== helloWorld
      len must beSome(helloWorld.length.toString)
    }

    "echo headers" in {
      val hs = Seq(
        "special1" -> "val1",
        "special2" -> "val2")

      val body = await(client.GET(makeUrl("/headers"), headers = hs)(ClientResponse.stringBody(_)))
      body must_== "special1: val1\nspecial2: val2\n"
    }

    "stream a response body" in {
      val resp = await {
        client.GET(makeUrl("/streaming"))(ClientResponse.stringBody(_))
      }
      resp must_== (0 until 1000).foldRight(""){ (remaining, acc) => acc + s"remaining: $remaining\n" }
    }

    "post a body" in {
      val b = "this is the body"
      val hs = Seq("content-length" -> b.length.toString)
      val req = HttpRequest(
        method = "POST",
        url = makeUrl("/post"),
        majorVersion = 1, minorVersion = 1,
        headers = hs,
        body = BodyReader.singleBuffer(StandardCharsets.UTF_8.encode(b))
      )

      val responseBody = await { client(req)(ClientResponse.stringBody(_)) }
      responseBody must_== s"You posted: $b"
    }
  }

  // Need to clean up after the spec is complete
  step {
    server.close()
    client.close()
  }
}
