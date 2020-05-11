/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
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
