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

import io.netty.handler.codec.http.HttpHeaders
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic._
import org.asynchttpclient._
import org.http4s.blaze.http._
import org.http4s.blaze.http.endtoend.scaffolds._
import org.specs2.mutable.Specification
import scala.concurrent._
import scala.util._

class InfiniteSpec extends Specification {
  implicit def ec: ExecutionContext = ExecutionContext.global

  "An infinite server response" should {
    "be properly cleaned on client close" in {
      val packetSize = 1024

      val bytesSentCount = new AtomicInteger(0)
      val bytesReceivedCount = new AtomicInteger(0)
      val lastMessageSentAt = new AtomicLong(-1)
      val streamInterrupted = new AtomicBoolean(false)

      val client = Dsl.asyncHttpClient()

      val service: HttpService = { request =>
        request.url match {
          case "/infinite" =>
            Future.successful {
              new RouteAction {
                override def handle[A, T <: BodyWriter[A]](responder: (HttpResponsePrelude) => T) = {
                  val writer = responder(HttpResponsePrelude(200, "OK", Nil))
                  val p = Promise[A]()
                  def go(): Unit =
                    writer
                      .write(
                        StandardCharsets.UTF_8.encode((0 to packetSize).map(_ => 'X').mkString))
                      .flatMap(_ => writer.flush())
                      .onComplete {
                        case Success(_) =>
                          bytesSentCount.addAndGet(packetSize)
                          go()
                        case Failure(e) =>
                          streamInterrupted.set(true)
                          p.tryFailure(e)
                      }
                  ec.execute(new Runnable { def run(): Unit = go() })
                  p.future
                }
              }
            }
        }
      }

      (new Http1ServerScaffold(service)) { address =>
        val request = new RequestBuilder()
        request.setMethod("GET")
        request.setUrl(s"http://localhost:${address.getPort}/infinite")

        // Interrupt this request client side after we received 10MB
        val completed =
          client
            .executeRequest(
              request,
              new AsyncHandler[Boolean] {
                override def onStatusReceived(status: HttpResponseStatus) = {
                  require(status.getStatusCode == 200)
                  AsyncHandler.State.CONTINUE
                }
                override def onBodyPartReceived(part: HttpResponseBodyPart) =
                  if (bytesReceivedCount.addAndGet(part.length) < (packetSize * 10))
                    AsyncHandler.State.CONTINUE
                  else
                    AsyncHandler.State.ABORT
                override def onHeadersReceived(headers: HttpHeaders) = {
                  require(headers.get("transfer-encoding") == "chunked")
                  require(headers.get("content-length") == null)
                  AsyncHandler.State.CONTINUE
                }
                override def onThrowable(e: Throwable) = ()
                override def onCompleted() = true
              }
            )
            .toCompletableFuture
            .get(10, TimeUnit.SECONDS)
        client.close()

        completed must beTrue

        // Assert that eventually, the server noticed that the client was gone and
        // properly cleaned its resources
        eventually {
          streamInterrupted.get must beTrue
          (System.currentTimeMillis - lastMessageSentAt.get) must beGreaterThanOrEqualTo(
            1000.toLong)
        }

        bytesReceivedCount.get must beGreaterThanOrEqualTo(packetSize * 10)
        bytesSentCount.get must beGreaterThanOrEqualTo(packetSize * 10)
      }
    }
  }
}
