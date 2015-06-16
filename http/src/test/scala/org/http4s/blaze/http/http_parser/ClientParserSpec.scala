package org.http4s.blaze.http.http_parser

import org.specs2.mutable._

import java.nio.charset.StandardCharsets.ISO_8859_1
import scala.collection.mutable.ListBuffer
import java.nio.ByteBuffer
import org.http4s.blaze.http.http_parser.BaseExceptions.{InvalidState, BadResponse}

class ClientParserSpec extends Specification {

  val resp = "HTTP/1.1 200 OK\r\n"

  val l_headers = ("From", "someuser@jmarshall.com  ")::
    ("HOST", "www.foo.com")::
    ("User-Agent", "HTTPTool/1.0  ")::
    ("Some-Header", "")::Nil

  val l_headersstr = l_headers.map{ case (a, b) => a + (if(b.length > 0) {": " + b} else "")}
    .mkString("\r\n") + "\r\n\r\n"

  val body = "Hello world!"
  
  val content_length = s"Content-Length: ${body.length}\r\n"

  def wrap(bytes: Array[Byte]) = ByteBuffer.wrap(bytes)

  class TestParser extends Http1ClientParser() {
    val headers = new ListBuffer[(String, String)]
    var code: Int = 0
    var reason: String = null
    var scheme: String = null
    var majorversion: Int = 0
    var minorversion: Int = 0

    def parseResponse(b: ByteBuffer) = this.parseResponseLine(b)

    def submitResponseLine(code: Int, reason: String, scheme: String, majorversion: Int, minorversion: Int) {
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

  "Client parser" should {
    "Fail on non-ascii char in status line" in {
      val p = new TestParser()
      val ch = "£"

      for (i <- 0 until resp.length) yield {
        p.reset()
        val (h, t) = resp.splitAt(i)
        val l2 = h + ch + t
        p.parseResponse(wrap(l2.getBytes(ISO_8859_1))) must throwA[BadResponse]
      }
    }

    "Parse the response line" in {
      val p = new TestParser

      p.parseResponse(wrap(resp.getBytes(ISO_8859_1))) should_== true
      p.responseLineComplete() should_== true
      p.code should_== 200
      p.reason should_== "OK"
      p.majorversion should_== 1
      p.minorversion should_== 1

      p.mayHaveBody() should_== true

      //
      p.reset()
      val line = "HTTP/1.0 200 OK\r\n"
      p.parseResponse(wrap(line.getBytes(ISO_8859_1))) should_== true
      p.responseLineComplete() should_== true
      p.minorversion should_== 0
    }

    "throw BadResponse on invalid response lines" in {
      val p = new TestParser

      val badVerison = "HTTP/1.7 200 OK\r\n"
      p.parseResponse(wrap(badVerison.getBytes(ISO_8859_1))) should throwA[BadResponse]

      p.reset()
      val weirdCode = "HTTP/1.1 200 OK\r\n"
      p.parseResponse(wrap(weirdCode.getBytes(ISO_8859_1)))

      p.reset()
      val badCodeChar = "HTTP/1.1 T200 OK\r\n"
      p.parseResponse(wrap(badCodeChar.getBytes(ISO_8859_1))) should throwA[BadResponse]

      p.reset()
      val missingSpace = "HTTP/1.1 200OK\r\n"
      p.parseResponse(wrap(missingSpace.getBytes(ISO_8859_1))) should throwA[BadResponse]

      p.reset()
      val noSpace = "HTTP/1.1 200OK\r\n"
      p.parseResponse(wrap(noSpace.getBytes(ISO_8859_1))) should throwA[BadResponse]

      p.reset()
      val badCode = "HTTP/1.1 20 OK\r\n"
      p.parseResponse(wrap(badCode.getBytes(ISO_8859_1))) should throwA[BadResponse]

      p.reset()
      val badCode2 = "HTTP/1.1 600 OK\r\n"
      p.parseResponse(wrap(badCode2.getBytes(ISO_8859_1))) should throwA[BadResponse]

      p.reset()
      val badLf = "HTTP/1.1 200 OK\r\r\n"
      p.parseResponse(wrap(badLf.getBytes(ISO_8859_1))) should throwA[BadResponse]
    }

    "throw invalid state if trying to parse the response line more than once" in {
      val p = new TestParser
      p.parseResponse(wrap(resp.getBytes(ISO_8859_1))) should_== (true)

      p.parseResponse(wrap(resp.getBytes(ISO_8859_1))) should throwA[InvalidState]
    }


    "Parse headers" in {
      val p = new TestParser
      val msg = resp + l_headersstr

      //println(msg.replace("\r\n", "\\r\\n\r\n"))

      val bts = wrap(msg.getBytes(ISO_8859_1))
      p.parseResponse(bts) should_== (true)
      p.responseLineComplete() should_== (true)

      p.parseheaders(bts) should_== (true)
      p.headersComplete() should_== (true)

      val stripedh = l_headers.map{ case (a, b) => (a.trim, b.trim) }
      p.headers.foldLeft(true){ (a, b) => a && stripedh.contains(b) } should_==(true)
    }
    
    "Parse a body with defined length" in {
      val p = new TestParser
      val full = resp + content_length + l_headersstr + body

//      println(full.replace("\r\n", "\\r\\n\r\n"))

      val bts = wrap(full.getBytes(ISO_8859_1))

      p.parseResponse(bts) should_== (true)
      p.parseheaders(bts) should_== (true)

      p.contentComplete() should_==(false)

      val out = p.parsebody(bts)
      out.remaining() should_==(body.length)

      ISO_8859_1.decode(out).toString should_==(body)
    }

    "Parse a chunked body" in {

      val p = new TestParser
      val full = resp + "Transfer-Encoding: chunked\r\n" + l_headersstr +
                  Integer.toHexString(body.length) + "\r\n" +
                  body + "\r\n" +
                  "0\r\n" +
                  "\r\n"

      val bts = wrap(full.getBytes(ISO_8859_1))

      p.parseResponse(bts) should_== (true)
      p.parseheaders(bts) should_== (true)

      p.contentComplete() should_==(false)

      val out = p.parsebody(bts)
      out.remaining() should_==(body.length)

      p.contentComplete() should_==(false)
      p.parsebody(bts).remaining() should_==(0)
      p.contentComplete() should_==(true)

      ISO_8859_1.decode(out).toString should_==(body)
    }

    "Parse a body with without Content-Length or Transfer-Encoding" in {
      val p = new TestParser
      val full = resp + l_headersstr + body

      val bts = wrap(full.getBytes(ISO_8859_1))

      p.parseResponse(bts) should_== (true)
      p.parseheaders(bts) should_== (true)

      p.contentComplete() should_==(false)

      val out = p.parsebody(bts)
      out.remaining() should_==(body.length)

      p.contentComplete() should_==(false)

      ISO_8859_1.decode(out).toString should_==(body)
    }

    "Parse a body with a Content-Length and `Transfer-Encoding: identity` header" in {
      val p = new TestParser
      val full = resp + content_length + "Transfer-Encoding: identity\r\n" + l_headersstr + body
      val bts = wrap(full.getBytes(ISO_8859_1))

      p.parseResponse(bts) should_== (true)
      p.parseheaders(bts) should_== (true)

      p.contentComplete() should_==(false)

      val out = p.parsebody(bts)
      out.remaining() should_==(body.length)

      p.contentComplete() should_==(true)

      ISO_8859_1.decode(out).toString should_==(body)
    }
  }

}
