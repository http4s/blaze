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
import java.nio.charset.StandardCharsets.ISO_8859_1

import org.http4s.blaze.http.parser.BaseExceptions.{BadMessage, InvalidState}
import org.http4s.blaze.testkit.BlazeTestSuite

import scala.collection.mutable.ListBuffer

class ClientParserSuite extends BlazeTestSuite {
  private val resp = "HTTP/1.1 200 OK\r\n"

  private val l_headers = ("From", "someuser@jmarshall.com  ") ::
    ("HOST", "www.foo.com") ::
    ("User-Agent", "HTTPTool/1.0  ") ::
    ("Some-Header", "") :: Nil

  private val l_headersstr = l_headers
    .map { case (a, b) =>
      a + (if (b.length > 0)
             ": " + b
           else "")
    }
    .mkString("\r\n") + "\r\n\r\n"

  private val body = "Hello world!"

  private val content_length = s"Content-Length: ${body.length}\r\n"

  private def wrap(bytes: Array[Byte]) = ByteBuffer.wrap(bytes)

  private class TestParser extends Http1ClientParser() {
    val headers = new ListBuffer[(String, String)]
    var code: Int = 0
    var reason: String = null
    var scheme: String = null
    var majorversion: Int = 0
    var minorversion: Int = 0

    def parseResponse(b: ByteBuffer) = this.parseResponseLine(b)

    def submitResponseLine(
        code: Int,
        reason: String,
        scheme: String,
        majorversion: Int,
        minorversion: Int): Unit = {
      this.code = code
      this.reason = reason
      this.scheme = scheme
      this.majorversion = majorversion
      this.minorversion = minorversion
    }

    def headerComplete(name: String, value: String) = {
      headers += ((name, value))
      false
    }

    def parseheaders(b: ByteBuffer) = super.parseHeaders(b)

    def parsebody(b: ByteBuffer) = super.parseContent(b)
  }

  test("A client parser should fail on non-ascii char in status line") {
    val p = new TestParser()
    val ch = "£"

    for (i <- 0 until resp.length) yield {
      p.reset()
      val (h, t) = resp.splitAt(i)
      val l2 = h + ch + t

      intercept[BadMessage](p.parseResponse(wrap(l2.getBytes(ISO_8859_1))))
    }
  }

  test("A client parser should parse the response line") {
    val p = new TestParser

    assert(p.parseResponse(wrap(resp.getBytes(ISO_8859_1))))
    assert(p.responseLineComplete())
    assertEquals(p.code, 200)
    assertEquals(p.reason, "OK")
    assertEquals(p.majorversion, 1)
    assertEquals(p.minorversion, 1)
    assertEquals(p.mustNotHaveBody(), false)

    p.reset()
    val line = "HTTP/1.0 200 OK\r\n"
    assert(p.parseResponse(wrap(line.getBytes(ISO_8859_1))))
    assert(p.responseLineComplete())
    assertEquals(p.minorversion, 0)
  }

  test("A client parser should throw BadResponse on invalid response lines") {
    val p = new TestParser

    val badVerison = "HTTP/1.7 200 OK\r\n"
    intercept[BadMessage](p.parseResponse(wrap(badVerison.getBytes(ISO_8859_1))))

    p.reset()
    val weirdCode = "HTTP/1.1 200 OK\r\n"
    p.parseResponse(wrap(weirdCode.getBytes(ISO_8859_1)))

    p.reset()
    val badCodeChar = "HTTP/1.1 T200 OK\r\n"
    intercept[BadMessage](p.parseResponse(wrap(badCodeChar.getBytes(ISO_8859_1))))

    p.reset()
    val missingSpace = "HTTP/1.1 200OK\r\n"
    intercept[BadMessage](p.parseResponse(wrap(missingSpace.getBytes(ISO_8859_1))))

    p.reset()
    val noSpace = "HTTP/1.1 200OK\r\n"
    intercept[BadMessage](p.parseResponse(wrap(noSpace.getBytes(ISO_8859_1))))

    p.reset()
    val badCode = "HTTP/1.1 20 OK\r\n"
    intercept[BadMessage](p.parseResponse(wrap(badCode.getBytes(ISO_8859_1))))

    p.reset()
    val badCode2 = "HTTP/1.1 600 OK\r\n"
    intercept[BadMessage](p.parseResponse(wrap(badCode2.getBytes(ISO_8859_1))))

    p.reset()
    val badLf = "HTTP/1.1 200 OK\r\r\n"
    intercept[BadMessage](p.parseResponse(wrap(badLf.getBytes(ISO_8859_1))))
  }

  test("A client parser should not fail on missing reason") {
    val p = new TestParser
    val missingReason = "HTTP/1.1 200 \r\n"
    assert(p.parseResponse(wrap(missingReason.getBytes(ISO_8859_1))))
    assert(p.responseLineComplete())
    assertEquals(p.code, 200)
    assertEquals(p.reason, "")

    p.reset()
    val missingReasonAndSpace = "HTTP/1.1 200\r\n"
    assert(p.parseResponse(wrap(missingReasonAndSpace.getBytes(ISO_8859_1))))
    assert(p.responseLineComplete())
    assertEquals(p.code, 200)
    assertEquals(p.reason, "")
  }

  test(
    "A client parser should throw invalid state if trying to parse the response line more than once") {
    val p = new TestParser
    assert(p.parseResponse(wrap(resp.getBytes(ISO_8859_1))))

    intercept[InvalidState](p.parseResponse(wrap(resp.getBytes(ISO_8859_1))))
  }

  test("A client parser should parse headers") {
    val p = new TestParser
    val msg = resp + l_headersstr

    // println(msg.replace("\r\n", "\\r\\n\r\n"))

    val bts = wrap(msg.getBytes(ISO_8859_1))
    assert(p.parseResponse(bts))
    assert(p.responseLineComplete())

    assert(p.parseheaders(bts))
    assert(p.headersComplete())

    val stripedh = l_headers.map { case (a, b) => (a.trim, b.trim) }

    assert(p.headers.foldLeft(true)((a, b) => a && stripedh.contains(b)))
  }

  test("A client parser should parse a body with defined length") {
    val p = new TestParser
    val full = resp + content_length + l_headersstr + body

    //      println(full.replace("\r\n", "\\r\\n\r\n"))

    val bts = wrap(full.getBytes(ISO_8859_1))

    assert(p.parseResponse(bts))
    assert(p.parseheaders(bts))

    assertEquals(p.contentComplete(), false)

    val out = p.parsebody(bts)
    assertEquals(out.remaining(), body.length)

    assertEquals(ISO_8859_1.decode(out).toString, body)
  }

  test("A client parser should parse a chunked body") {
    val p = new TestParser
    val full = resp + "Transfer-Encoding: chunked\r\n" + l_headersstr +
      Integer.toHexString(body.length) + "\r\n" +
      body + "\r\n" +
      "0\r\n" +
      "\r\n"

    val bts = wrap(full.getBytes(ISO_8859_1))

    assert(p.parseResponse(bts))
    assert(p.parseheaders(bts))

    assertEquals(p.contentComplete(), false)

    val out = p.parsebody(bts)
    assertEquals(out.remaining(), body.length)

    assertEquals(p.contentComplete(), false)
    assertEquals(p.parsebody(bts).remaining(), 0)
    assert(p.contentComplete())

    assertEquals(ISO_8859_1.decode(out).toString, body)
  }

  test("A client parser should parse a body with without Content-Length or Transfer-Encoding") {
    val p = new TestParser
    val full = resp + l_headersstr + body

    val bts = wrap(full.getBytes(ISO_8859_1))

    assert(p.parseResponse(bts))
    assert(p.parseheaders(bts))

    assertEquals(p.contentComplete(), false)

    val out = p.parsebody(bts)
    assertEquals(out.remaining(), body.length)

    assertEquals(p.contentComplete(), false)

    assertEquals(ISO_8859_1.decode(out).toString, body)
  }

  test(
    "A client parser should parse a body with a Content-Length and `Transfer-Encoding: identity` header") {
    val p = new TestParser
    val full = resp + content_length + "Transfer-Encoding: identity\r\n" + l_headersstr + body
    val bts = wrap(full.getBytes(ISO_8859_1))

    assert(p.parseResponse(bts))
    assert(p.parseheaders(bts))

    assertEquals(p.contentComplete(), false)

    val out = p.parsebody(bts)
    assertEquals(out.remaining(), body.length)

    assert(p.contentComplete())

    assertEquals(ISO_8859_1.decode(out).toString, body)
  }
}
