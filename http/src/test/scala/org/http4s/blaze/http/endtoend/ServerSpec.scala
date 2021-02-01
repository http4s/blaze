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

package org.http4s.blaze.http.endtoend

import java.net.{InetAddress, InetSocketAddress}
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.endtoend.scaffolds.ClientScaffold.Response
import org.http4s.blaze.http.endtoend.scaffolds._
import org.http4s.blaze.http.{BodyReader, HttpService, RouteAction}
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class Http1ServerSpec extends BaseServerSpec(false) {
  override def newServer(service: HttpService): ServerScaffold = new Http1ServerScaffold(service)

  override def newClient(): ClientScaffold = new AsyncHttp1ClientScaffold(10.seconds)
}

class Http2ServerSpec extends BaseServerSpec(isSecure = true) {
  override def newServer(service: HttpService): ServerScaffold = new Http2ServerScaffold(service)

  override def newClient(): ClientScaffold = new Http2ClientScaffold
}

abstract class BaseServerSpec(isSecure: Boolean) extends Specification {
  implicit def ec = ExecutionContext.global

  def newServer(service: HttpService): ServerScaffold

  def newClient(): ClientScaffold

  private val helloWorld = "Hello, world!"

  val service: HttpService = { request =>
    request.url match {
      case "/hello" => Future.successful(RouteAction.Ok(helloWorld))
      case "/headers" =>
        Future.successful {
          val str = request.headers
            .filter(_._1.startsWith("special"))
            .foldLeft("") { case (acc, (k, v)) => acc + s"$k: $v\n" }

          RouteAction.Ok(str)
        }

      case "/streaming" =>
        Future.successful {
          var remaining = 1000
          RouteAction.Streaming(200, "OK", Nil) {
            if (remaining == 0) Future.successful(BufferTools.emptyBuffer)
            else {
              remaining -= 1
              Future.successful(StandardCharsets.UTF_8.encode(s"remaining: $remaining\n"))
            }
          }
        }

      case "/post" if request.method == "POST" =>
        request.body.accumulate().map { bytes =>
          val body = StandardCharsets.UTF_8.decode(bytes).toString
          RouteAction.Ok(s"You posted: $body")
        }

      case "/echo" if request.method == "POST" =>
        Future.successful {
          RouteAction.Streaming(200, "OK", Nil)(request.body())
        }

      case url => Future.successful(RouteAction.Ok(s"url: $url"))
    }
  }

  private val client = newClient()

  private def makeUrl(address: InetSocketAddress, uri: String): String = {
    val scheme = if (isSecure) "https" else "http"
    val hostName = InetAddress.getLoopbackAddress.getCanonicalHostName
    val port = address.getPort
    s"$scheme://$hostName:$port$uri"
  }

  "blaze server" should {
    "do a hello world request" in {
      newServer(service) { address =>
        val Response(resp, body) = client.runGet(makeUrl(address, "/hello"))
        new String(body, StandardCharsets.UTF_8) must_== helloWorld
        val clen = resp.headers.collectFirst {
          case (k, v) if k.equalsIgnoreCase("content-length") => v
        }
        clen must beSome(helloWorld.length.toString)
      }
    }

    "echo headers" in {
      val hs = Seq("special1" -> "val1", "special2" -> "val2")
      newServer(service) { address =>
        val Response(_, body) = client.runGet(makeUrl(address, "/headers"), hs)
        new String(body, StandardCharsets.UTF_8) must_== "special1: val1\nspecial2: val2\n"
      }
    }

    "stream a response body" in {
      newServer(service) { address =>
        val Response(_, body) = client.runGet(makeUrl(address, "/streaming"))
        val responseString = new String(body, StandardCharsets.UTF_8)
        responseString must_== (0 until 1000).foldRight("") { (remaining, acc) =>
          acc + s"remaining: $remaining\n"
        }
      }
    }

    "post a body" in {
      val body = "this is the body"
      val bodyReader = BodyReader.singleBuffer(StandardCharsets.UTF_8.encode(body))

      newServer(service) { address =>
        val Response(_, body) = client.runPost(makeUrl(address, "/post"), bodyReader)
        new String(body, StandardCharsets.UTF_8) must_== "You posted: this is the body"
      }
    }
  }

  // Need to clean up after the spec is complete
  step {
    client.close()
  }
}
