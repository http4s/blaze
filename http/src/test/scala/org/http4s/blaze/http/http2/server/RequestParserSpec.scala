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

package org.http4s.blaze.http.http2.server

import org.http4s.blaze.http._
import org.specs2.mutable.Specification

class RequestParserSpec extends Specification {
  "RequestParser" >> {
    "prepares a valid incoming request" >> {
      val hs = Seq(
        ":method" -> "GET",
        ":scheme" -> "https",
        ":path" -> "/",
        "other" -> "funny header"
      )
      RequestParser.makeRequest(hs, BodyReader.EmptyBodyReader) must beLike {
        case Right(HttpRequest("GET", "/", 2, 0, headers, BodyReader.EmptyBodyReader)) =>
          headers must_== Seq("other" -> "funny header")
      }
    }

    "rejects invalid pseudo headers" >> {
      val invalidHs = Seq(
        Seq(), // empty
        Seq( // missing :method pseudo header
          ":scheme" -> "https",
          ":path" -> "/",
          "other" -> "funny header"),
        Seq( // multiple :method pseudo headers
          ":method" -> "GET",
          ":method" -> "GET",
          ":scheme" -> "https",
          ":path" -> "/",
          "other" -> "funny header"),
        Seq( // missing :scheme pseudo header
          ":method" -> "GET",
          ":path" -> "/",
          "other" -> "funny header"),
        Seq( // multiple :scheme pseudo headers
          ":method" -> "GET",
          ":scheme" -> "https",
          ":scheme" -> "https",
          ":path" -> "/",
          "other" -> "funny header"),
        Seq( // missing :path pseudo header
          ":method" -> "GET",
          ":scheme" -> "https",
          "other" -> "funny header"),
        Seq( // multiple :path pseudo headers
          ":method" -> "GET",
          ":scheme" -> "https",
          ":path" -> "/",
          ":path" -> "/",
          "other" -> "funny header"),
        Seq( // undefined pseudo header
          ":method" -> "GET",
          ":scheme" -> "https",
          ":path" -> "/",
          ":undefined" -> "",
          "other" -> "funny header"),
        Seq( // pseudo header after normal headers
          ":method" -> "GET",
          ":scheme" -> "https",
          "other" -> "funny header",
          ":path" -> "/"),
        Seq( // illegal header name
          ":method" -> "GET",
          ":scheme" -> "https",
          ":path" -> "/",
          "illega l" -> "cant have spaces in the name"),
        Seq( // cannot have Connection specific headers
          ":method" -> "GET",
          ":scheme" -> "https",
          ":path" -> "/",
          "connection" -> "sadface"),
        Seq( // TE other than 'trailers'
          ":method" -> "GET",
          ":scheme" -> "https",
          ":path" -> "/",
          "te" -> "chunked")
      )

      forall(invalidHs) { hs =>
        RequestParser.makeRequest(hs, BodyReader.EmptyBodyReader) must beLeft
      }
    }
  }
}
