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

package org.http4s.blaze.http.client

import org.http4s.blaze.http.{ClientResponse, HttpClient}
import org.specs2.mutable._

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class HttpClientSpec extends Specification {
  val client = HttpClient.pooledHttpClient

  // TODO: we shouldn't be calling out to external resources for tests
  "HttpClient" should {
    "Make https requests" in {
      val path = "https://github.com/"
      val hs = Seq("host" -> "github.com")

      val f = client.GET(path, hs)(r => ClientResponse.stringBody(r).map((_, r.code)))
      val (body, code) = Await.result(f, 10.seconds)
      println(s"Body: $body")
      code must_== 200
    }
  }
}
