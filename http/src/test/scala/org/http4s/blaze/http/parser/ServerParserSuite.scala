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

package org.http4s.blaze.http.parser

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.parser.BaseExceptions.{BadMessage, InvalidState}
import org.http4s.blaze.http.parser.BodyAndHeaderParser.EndOfContent
import org.http4s.blaze.testkit.BlazeTestSuite

import scala.collection.mutable.ListBuffer

class ServerParserSuite extends BlazeTestSuite {
  private implicit def strToBuffer(str: String): ByteBuffer =
    ByteBuffer.wrap(str.getBytes(StandardCharsets.ISO_8859_1))

  private class Parser(maxReq: Int = 1034, maxHeader: Int = 1024)
      extends Http1ServerParser(maxReq, maxHeader, 1) {
    val sb = new StringBuilder

    val h = new ListBuffer[(String, String)]

    def parseLine(s: ByteBuffer) = parseRequestLine(s)

    def parseheaders(s: ByteBuffer): Boolean = parseHeaders(s)

    def parsecontent(s: ByteBuffer): ByteBuffer = {
      val c = super.parseContent(s)
      if (c != null) {
        c.mark()
        while (c.hasRemaining) sb.append(c.get().toChar)
        c.reset()
      }
      c
    }

    var minorv = -1

    def submitRequestLine(
        methodString: String,
        uri: String,
        scheme: String,
        majorversion: Int,
        minorversion: Int) = {
      //      println(s"$methodString, $uri, $scheme/$majorversion.$minorversion")
      minorv = minorversion
      false
    }

    def headerComplete(name: String, value: String) = {
      // println(s"Found header: '$name': '$value'")
      h += ((name, value))
      false
    }
  }

  private def toChunk(str: String): String = s"${Integer.toHexString(str.length)}\r\n$str\r\n"

  private val l_headers = ("From", "someuser@jmarshall.com  ") ::
    ("HOST", "www.foo.com") ::
    ("User-Agent", "HTTPTool/1.0  ") ::
    ("Some-Header", "") :: Nil

  private val request = "POST /enlighten/calais.asmx HTTP/1.1\r\n"
  private val host = "HOST: www.foo.com\r\n"

  private def buildHeaderString(hs: Seq[(String, String)]): String =
    hs.foldLeft(new StringBuilder) { (sb, h) =>
      sb.append(h._1)
      if (h._2.length > 0) sb.append(": " + h._2)
      sb.append("\r\n")
    }.append("\r\n")
      .result()

  private val headers = buildHeaderString(l_headers)

  private val body = "hello world"

  private val lengthh = s"Content-Length: ${body.length}\r\n"

  private val chunked = "Transfer-Encoding: chunked\r\n"

  private val mockFiniteLength = request + host + lengthh + headers + body

  private val mockChunked =
    request + host + chunked + headers + toChunk(body) + toChunk(
      body + " again!") + "0 \r\n" + "\r\n"

  test("An Http1ServerParser should fail on non-ascii char in request line") {
    val p = new Parser()
    val line = "POST /enlighten/calais.asmx HTTP/1.1\r\n"
    val ch = "£"

    for (i <- 0 until line.length) yield {
      p.reset()
      val (h, t) = line.splitAt(i)
      val l2 = h + ch + t

      intercept[BadMessage](p.parseLine(l2))
    }
  }

  test("An Http1ServerParser should parse the request line for HTTP") {
    assert(new Parser().parseLine("POST /enlighten/calais.asmx HTTP/1.1\r\n"))

    assert(new Parser().parseLine("POST /enlighten/calais.asmx HTTP/1.1\n"))
  }

  test("An Http1ServerParser should parse the request line for HTTP in segments") {
    val p = new Parser()
    assertEquals(p.parseLine("POST /enlighten/cala"), false)
    assert(p.parseLine("is.asmx HTTP/1.1\r\n"))
  }

  test("An Http1ServerParser should parse the request line for HTTPS") {
    val p = new Parser()
    assert(p.parseLine("POST /enlighten/calais.asmx HTTPS/1.1\r\n"))
    assertEquals(p.minorv, 1)
  }

  test("An Http1ServerParser should give bad request on invalid request line") {
    val p = new Parser()
    intercept[BadMessage](p.parseLine("POST /enlighten/calais.asmx KTTPS/1.1\r\n"))

    p.reset()
    intercept[BadMessage](p.parseLine("POST /enlighten/calais.asmx HKTTPS/1.1\r\n"))

    p.reset()
    intercept[BadMessage](p.parseLine("POST=/enlighten/calais.asmx HKTTPS/1.1\r\n"))
  }

  test("An Http1ServerParser should give bad request on negative content-length") {
    val p = new Parser()
    val line = "GET /enlighten/calais.asmx HTTPS/1.0\r\n"

    assert(p.parseLine(line))
    intercept[BadMessage](p.parseheaders(buildHeaderString(Seq("content-length" -> "-1"))))
  }

  test("An Http1ServerParser should accept multiple same length content-length headers") {
    val p = new Parser()
    val line = "GET /enlighten/calais.asmx HTTPS/1.0\r\n"
    assert(p.parseLine(line))
    assert(p.parseheaders(buildHeaderString(Seq("content-length" -> "1", "content-length" -> "1"))))
  }

  test(
    "An Http1ServerParser should give bad request on multiple different content-length headers") {
    val p = new Parser()
    val line = "GET /enlighten/calais.asmx HTTPS/1.0\r\n"

    assert(p.parseLine(line))

    intercept[BadMessage](
      p.parseheaders(buildHeaderString(Seq("content-length" -> "1", "content-length" -> "2"))))
  }

  test("An Http1ServerParser should match Http1.0 requests") {
    val p = new Parser()
    p.parseLine("POST /enlighten/calais.asmx HTTPS/1.0\r\n")
    assertEquals(p.minorv, 0)
  }

  test("An Http1ServerParser should throw an invalid state if the request line is already parsed") {
    val p = new Parser()
    val line = "POST /enlighten/calais.asmx HTTPS/1.0\r\n"
    p.parseLine(line)
    intercept[InvalidState](p.parseLine(line))
  }

  test("An Http1ServerParser should parse headers") {
    val p = new Parser()
    val line = "GET /enlighten/calais.asmx HTTPS/1.0\r\n"
    p.parseLine(line)

    assert(p.parseheaders(headers))
    assertEquals(p.getContentType, EndOfContent.END)
    assertEquals(p.h.result(), l_headers.map { case (a, b) => (a.trim, b.trim) })
  }

  test("An Http1ServerParser should fail on non-ascii char in header name") {
    val p = new Parser()
    val ch = "£"
    val k = "Content-Length"

    for (i <- 0 until k.length) yield {
      p.reset()
      val (h, t) = k.splitAt(i)
      val hs = (h + ch + t, "0") :: Nil
      intercept[BadMessage](p.parseheaders(buildHeaderString(hs)))
    }
  }

  test("An Http1ServerParser should allow non-ascii char in header value") {
    val p = new Parser()
    val ch = "£"
    val k = "Foo-Header"
    val v = "this_is_some_header_value"

    for (i <- 0 until v.length) yield {
      p.reset()
      val (h, t) = v.splitAt(i)
      val hs = (k, h + ch + t) :: Nil
      assert(p.parseheaders(buildHeaderString(hs)))
    }
  }

  test("An Http1ServerParser should accept headers without values") {
    val hsStr =
      "If-Modified-Since\r\nIf-Modified-Since:\r\nIf-Modified-Since: \r\nIf-Modified-Since:\t\r\n\r\n"
    val p = new Parser()
    assert(p.parseheaders(hsStr))
    assertEquals(
      p.getContentType,
      EndOfContent.END
    ) // since the headers didn't indicate any content
    assertEquals(p.h.result(), List.fill(4)(("If-Modified-Since", "")))
  }

  test("An Http1ServerParser should need input on partial headers") {
    val p = new Parser()
    assertEquals(p.parseHeaders(headers.substring(0, 20)), false)
    assert(p.parseheaders(headers.substring(20)))
    assertEquals(p.h.result(), l_headers.map { case (a, b) => (a.trim, b.trim) })
  }

  test("An Http1ServerParser should parse a full request") {
    val p = new Parser()
    val b = strToBuffer(mockFiniteLength)

    assert(p.parseLine(b))

    assert(p.parseheaders(b))

    assertEquals(p.sb.result(), "")

    assert(p.parsecontent(b) != null)
    assertEquals(p.sb.result(), body)
    assert(p.contentComplete(), true)

    p.reset()
    assertEquals(p.requestLineComplete(), false)
  }

  test("An Http1ServerParser should parse a full request in fragments") {
    val p = new Parser()
    val b = strToBuffer(mockFiniteLength)

    b.limit()

    b.limit(1)

    while (!p.requestLineComplete() && !p.parseLine(b))
      b.limit(b.limit() + 1)

    while (!p.headersComplete() && !p.parseheaders(b))
      b.limit(b.limit() + 1)

    while (!p.contentComplete())
      if (null == p.parsecontent(b)) b.limit(b.limit() + 1)

    assertEquals(p.sb.result(), body)

    p.reset()
    assertEquals(p.requestLineComplete(), false)
  }

  test("An Http1ServerParser should parse a chunked request") {
    val p = new Parser()
    val b = strToBuffer(mockChunked)

    assert(p.parseLine(b))

    assert(p.parseheaders(b))
    assertEquals(p.sb.result(), "")

    assert(p.parsecontent(b) != null)
    assert(p.parsecontent(b) != null)
    // two real messages
    assertEquals(p.parsecontent(b).remaining(), 0)
    assertEquals(p.sb.result(), body + body + " again!")

    p.reset()
  }

  test("An Http1ServerParser should parse a chunked request with trailers") {
    val p = new Parser()
    val req = mockChunked.substring(0, mockChunked.length - 2) + "Foo\r\n\r\n"
    val b = strToBuffer(req)

    // println(mockChunked)

    assert(p.parseLine(b))

    assert(p.parseheaders(b))
    assertEquals(p.sb.result(), "")
    p.h.clear()

    assert(p.parsecontent(b) != null)
    assert(p.parsecontent(b) != null)
    // two real messages
    assertEquals(p.parsecontent(b).remaining(), 0)
    assertEquals(p.h.result(), ("Foo", "") :: Nil)
    assertEquals(p.sb.result(), body + body + " again!")

    p.reset()
  }

  test("An Http1ServerParser should give parse a chunked request in fragments") {
    val p = new Parser()
    val b = strToBuffer(mockChunked)
    val blim = b.limit()

    // Do it one char at a time /////////////////////////////////////////
    b.limit(1)
    b.position(0)
    p.sb.clear()

    while (!p.requestLineComplete() && !p.parseLine(b))
      b.limit(b.limit() + 1)

    while (!p.headersComplete() && !p.parseheaders(b))
      b.limit(b.limit() + 1)

    assertEquals(p.contentComplete(), false)

    while (!p.contentComplete()) {
      p.parsecontent(b)
      if (b.limit() < blim) b.limit(b.limit() + 1)
    }

    assert(p.contentComplete(), true)
    assertEquals(p.sb.result(), body + body + " again!")
  }

  test("An Http1ServerParser should give parse a chunked request in fragments with a trailer") {
    val p = new Parser()
    val req = mockChunked.substring(0, mockChunked.length - 2) + "Foo\r\n\r\n"
    val b = strToBuffer(req)
    val blim = b.limit()

    // Do it one char at a time /////////////////////////////////////////
    b.limit(1)
    b.position(0)
    p.sb.clear()

    while (!p.requestLineComplete() && !p.parseLine(b))
      b.limit(b.limit() + 1)

    while (!p.headersComplete() && !p.parseheaders(b))
      b.limit(b.limit() + 1)

    p.h.clear()

    assertEquals(p.contentComplete(), false)

    while (!p.contentComplete()) {
      p.parsecontent(b)
      if (b.limit() < blim) b.limit(b.limit() + 1)
    }

    assertEquals(p.h.result(), ("Foo", "") :: Nil)
    assert(p.contentComplete())
    assertEquals(p.sb.result(), body + body + " again!")
  }

  test("An Http1ServerParser should throw an error if the headers are too long") {
    val header = "From: someuser@jmarshall.com  \r\n" +
      "HOST: www.foo.com\r\n" +
      "User-Agent: HTTPTool/1.0  \r\n" +
      "Some-Header\r\n"

    val p = new Parser(maxHeader = header.length - 1)
    intercept[BadMessage](p.parseheaders(header))
  }

  test("An Http1ServerParser should throw an error if the request line is too long") {
    val p = new Parser(maxReq = request.length - 1)
    intercept[BadMessage](p.parseLine(request))
  }
}
