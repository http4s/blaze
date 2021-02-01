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
