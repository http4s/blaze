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

package org.http4s.blaze.http.http1.client

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.parser.BaseExceptions.BadMessage
import org.http4s.blaze.http.{HttpClientConfig, HttpResponsePrelude}
import org.specs2.mutable.Specification

class Http1ClientCodecSpec extends Specification {
  val config = HttpClientConfig.Default

  def buffer(str: String): ByteBuffer =
    ByteBuffer.wrap(str.getBytes(StandardCharsets.US_ASCII))

  "Http1ClientCodec" should {
    "parse a standard prelude" in {
      val codec = new Http1ClientCodec(config)
      codec.parsePrelude(
        buffer("HTTP/1.1 200 OK\r\n" +
          "Content-Length: 10\r\n\r\n")) must beTrue

      codec.getResponsePrelude must_== HttpResponsePrelude(200, "OK", Seq("Content-Length" -> "10"))
    }

    "parse a standard prelude in chunks" in {
      val codec = new Http1ClientCodec(config)
      codec.parsePrelude(buffer("HTTP/1.1 200 OK\r\n")) must beFalse
      codec.parsePrelude(buffer("Content-Length: 10\r\n\r\n")) must beTrue

      codec.getResponsePrelude must_== HttpResponsePrelude(200, "OK", Seq("Content-Length" -> "10"))
    }

    "reject too long of a status line" in {
      val line = "HTTP/1.1 200 OK\r\n"

      new Http1ClientCodec(config.copy(maxResponseLineLength = line.length))
        .parsePrelude(buffer(line)) must beFalse // OK

      new Http1ClientCodec(config.copy(maxResponseLineLength = line.length - 1))
        .parsePrelude(buffer(line)) must throwA[BadMessage]
    }

    "reject too long of headers" in {
      val clen = "Content-Length: 10\r\n\r\n"
      val resp = "HTTP/1.1 200 OK\r\n" + clen

      new Http1ClientCodec(config.copy(maxHeadersLength = clen.length))
        .parsePrelude(buffer(resp)) must beTrue // OK

      new Http1ClientCodec(config.copy(maxHeadersLength = clen.length - 1))
        .parsePrelude(buffer(resp)) must throwA[BadMessage]
    }
  }
}
