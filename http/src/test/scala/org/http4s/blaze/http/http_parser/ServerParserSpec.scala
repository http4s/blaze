package org.http4s.blaze.http.http_parser

import java.nio.charset.StandardCharsets

import org.specs2.mutable._

import java.nio.ByteBuffer
import org.http4s.blaze.http.http_parser.BodyAndHeaderParser.EndOfContent
import org.http4s.blaze.http.http_parser.BaseExceptions.{InvalidState, BadRequest}
import scala.collection.mutable.ListBuffer

class ServerParserSpec extends Specification {

  implicit def strToBuffer(str: String) = ByteBuffer.wrap(str.getBytes(StandardCharsets.ISO_8859_1))

  class Parser(maxReq: Int = 1034, maxHeader: Int = 1024) extends Http1ServerParser(maxReq, maxHeader, 1) {

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

    def submitRequestLine(methodString: String, uri: String, scheme: String, majorversion: Int, minorversion: Int) = {
      //      println(s"$methodString, $uri, $scheme/$majorversion.$minorversion")
      minorv = minorversion
      false
    }

    def headerComplete(name: String, value: String) = {
      //println(s"Found header: '$name': '$value'")
      h += ((name, value))
      false
    }
  }

  def toChunk(str: String): String = {
    val len = Integer.toHexString(str.length) + "\r\n"
    len +
      str +
      "\r\n"
  }

  val l_headers = ("From", "someuser@jmarshall.com  ") ::
    ("HOST", "www.foo.com") ::
    ("User-Agent", "HTTPTool/1.0  ") ::
    ("Some-Header", "") :: Nil


  val request = "POST /enlighten/calais.asmx HTTP/1.1\r\n"
  val host = "HOST: www.foo.com\r\n"

  val http10 = "GET /path/file.html HTTP/1.0\r\n"

  //  val header =  "From: someuser@jmarshall.com  \r\n" +
  //                "HOST: www.foo.com\r\n" +
  //                "User-Agent: HTTPTool/1.0  \r\n" +
  //                "Some-Header\r\n" +
  //                "\r\n"


  def buildHeaderString(hs: Seq[(String, String)]): String = {
    hs.foldLeft(new StringBuilder) { (sb, h) =>
      sb.append(h._1)
      if (h._2.length > 0) sb.append(": " + h._2)
      sb.append("\r\n")
    }.append("\r\n").result
  }

  val header = buildHeaderString(l_headers)

  val body = "hello world"

  val lengthh = s"Content-Length: ${body.length}\r\n"

  val chunked = "Transfer-Encoding: chunked\r\n"

  val mockFiniteLength = request + host + lengthh + header + body

  val mockChunked = request + host + chunked + header + toChunk(body) + toChunk(body + " again!") + "0 \r\n" + "\r\n"

  val twoline = request + host

  "Http1ServerParser" should {
    "Fail on non-ascii char in request line" in {
      val p = new Parser()
      val line = "POST /enlighten/calais.asmx HTTP/1.1\r\n"
      val ch = "£"

      for (i <- 0 until line.length) yield {
        p.reset()
        val (h, t) = line.splitAt(i)
        val l2 = h + ch + t
        p.parseLine(l2) must throwA[BadRequest]
      }
    }

    "Parse the request line for HTTP" in {
      val p = new Parser()
      p.parseLine("POST /enlighten/calais.asmx HTTP/1.1\r\n") should_== (true)

      //      p.s should_== ("Request('POST', '/enlighten/calais.asmx', 'http', 1.1)")
      //      p.getState() should_== (ParserState.Idle)
    }

    "Parse the request line for HTTP in segments" in {
      val p = new Parser()
      p.parseLine("POST /enlighten/cala") should_== (false)
      p.parseLine("is.asmx HTTP/1.1\r\n") should_== (true)

      //      p.s should_== ("Request('POST', '/enlighten/calais.asmx', 'http', 1.1)")
      //      p.getState() should_== (ParserState.Idle)
    }


    "Parse the request line for HTTPS" in {
      val p = new Parser()
      p.parseLine("POST /enlighten/calais.asmx HTTPS/1.1\r\n") should_== (true)
      p.minorv should_== (1)
    }

    "Give bad request on invalid request line" in {
      val p = new Parser()
      p.parseLine("POST /enlighten/calais.asmx KTTPS/1.1\r\n") should throwA[BadRequest]

      p.reset()
      p.parseLine("POST /enlighten/calais.asmx HKTTPS/1.1\r\n") should throwA[BadRequest]

      p.reset()
      p.parseLine("POST=/enlighten/calais.asmx HKTTPS/1.1\r\n") should throwA[BadRequest]
    }

    "Match Http1.0 requests" in {
      val p = new Parser()
      p.parseLine("POST /enlighten/calais.asmx HTTPS/1.0\r\n")
      p.minorv should_== (0)
    }

    "throw an invalid state if the request line is already parsed" in {
      val p = new Parser()
      val line = "POST /enlighten/calais.asmx HTTPS/1.0\r\n"
      p.parseLine(line)
      p.parseLine(line) should throwAn[InvalidState]
    }

    "Parse headers" in {
      val p = new Parser()
      p.parseheaders(header) should_== (true)
      p.getContentType should_== (EndOfContent.UNKNOWN_CONTENT)
      p.h.result should_== (l_headers.map { case (a, b) => (a.trim, b.trim)})
    }

    "Fail on non-ascii char in header name" in {
      val p = new Parser()
      val ch = "£"
      val k = "Content-Length"

      for (i <- 0 until k.length) yield {
        p.reset()
        val (h, t) = k.splitAt(i)
        val hs = (h + ch + t, "0") :: Nil
        p.parseheaders(buildHeaderString(hs)) must throwA[BadRequest]
      }
    }

    "Allow non-ascii char in header value" in {
      val p = new Parser()
      val ch = "£"
      val k = "Foo-Header"
      val v = "this_is_some_header_value"

      for (i <- 0 until v.length) yield {
        p.reset()
        val (h, t) = v.splitAt(i)
        val hs = (k, h + ch + t) :: Nil
        p.parseheaders(buildHeaderString(hs)) must_== true
      }

      "Accept headers without values" in {
        val hsStr = "If-Modified-Since\r\nIf-Modified-Since:\r\nIf-Modified-Since: \r\nIf-Modified-Since:\t\r\n\r\n"
        val p = new Parser()
        p.parseheaders(hsStr) should_== (true)
        p.getContentType should_== (EndOfContent.UNKNOWN_CONTENT)
        p.h.result should_== List.fill(4)(("If-Modified-Since", ""))

      }

      "need input on partial headers" in {
        val p = new Parser()
        p.parseHeaders(header.slice(0, 20)) should_== (false)
        p.parseheaders(header.substring(20)) should_== (true)
        p.h.result should_== (l_headers.map { case (a, b) => (a.trim, b.trim)})
      }

      "Parse a full request" in {
        val p = new Parser()
        val b = ByteBuffer.wrap(mockFiniteLength.getBytes())

        p.parseLine(b) should_== (true)

        p.parseheaders(b) should_== (true)

        p.sb.result() should_== ("")

        p.parsecontent(b) should_!= (null)
        p.sb.result() should_== (body)
        p.contentComplete() should_== (true)


        p.reset()
        p.requestLineComplete() should_== (false)
      }

      "Parse a full request in fragments" in {
        val p = new Parser()
        val b = ByteBuffer.wrap(mockFiniteLength.getBytes())

        val blim = b.limit()

        b.limit(1)

        while (!p.requestLineComplete() && !p.parseLine(b)) {
          b.limit(b.limit() + 1)
        }

        while (!p.headersComplete() && !p.parseheaders(b)) {
          b.limit(b.limit() + 1)
        }

        while (!p.contentComplete()) {
          if (null == p.parsecontent(b)) b.limit(b.limit() + 1)
        }

        p.sb.result() should_== (body)

        p.reset()
        p.requestLineComplete() should_== (false)
      }

      "Parse a chunked request" in {
        val p = new Parser()
        val b = ByteBuffer.wrap(mockChunked.getBytes())

        p.parseLine(b) should_== (true)

        p.parseheaders(b) should_== (true)
        p.sb.result() should_== ("")

        p.parsecontent(b) should_!= (null)
        p.parsecontent(b) should_!= (null)
        // two real messages
        p.parsecontent(b).remaining() should_== (0)
        p.sb.result() should_== (body + body + " again!")

        p.reset()
        true should_== true
      }

      "Parse a chunked request with trailers" in {
        val p = new Parser()
        val req = mockChunked.substring(0, mockChunked.length - 2) + "Foo\r\n\r\n"
        val b = ByteBuffer.wrap(req.getBytes())

        println(mockChunked)

        p.parseLine(b) should_== (true)

        p.parseheaders(b) should_== (true)
        p.sb.result() should_== ("")
        p.h.clear()

        p.parsecontent(b) should_!= (null)
        p.parsecontent(b) should_!= (null)
        // two real messages
        p.parsecontent(b).remaining() should_== (0)
        p.h.result should_== (("Foo", "") :: Nil)
        p.sb.result() should_== (body + body + " again!")

        p.reset()
        true should_== true
      }

      "Give parse a chunked request in fragments" in {
        val p = new Parser()
        val b = ByteBuffer.wrap(mockChunked.getBytes())
        val blim = b.limit()

        // Do it one char at a time /////////////////////////////////////////
        b.limit(1)
        b.position(0)
        p.sb.clear()


        while (!p.requestLineComplete() && !p.parseLine(b)) {
          b.limit(b.limit() + 1)
        }

        while (!p.headersComplete() && !p.parseheaders(b)) {
          b.limit(b.limit() + 1)
        }

        p.contentComplete() should_== (false)

        while (!p.contentComplete()) {
          p.parsecontent(b)
          if (b.limit < blim) b.limit(b.limit() + 1)
        }

        p.contentComplete() should_== (true)
        p.sb.result() should_== (body + body + " again!")
      }

      "Give parse a chunked request in fragments with a trailer" in {
        val p = new Parser()
        val req = mockChunked.substring(0, mockChunked.length - 2) + "Foo\r\n\r\n"
        val b = ByteBuffer.wrap(req.getBytes())
        val blim = b.limit()

        // Do it one char at a time /////////////////////////////////////////
        b.limit(1)
        b.position(0)
        p.sb.clear()


        while (!p.requestLineComplete() && !p.parseLine(b)) {
          b.limit(b.limit() + 1)
        }

        while (!p.headersComplete() && !p.parseheaders(b)) {
          b.limit(b.limit() + 1)
        }

        p.h.clear

        p.contentComplete() should_== (false)

        while (!p.contentComplete()) {
          p.parsecontent(b)
          if (b.limit < blim) b.limit(b.limit() + 1)
        }
        p.h.result should_== (("Foo", "") :: Nil)
        p.contentComplete() should_== (true)
        p.sb.result() should_== (body + body + " again!")
      }

      "throw an error if the headers are too long" in {
        val header = "From: someuser@jmarshall.com  \r\n" +
          "HOST: www.foo.com\r\n" +
          "User-Agent: HTTPTool/1.0  \r\n" +
          "Some-Header\r\n"

        val p = new Parser(maxHeader = header.length - 1)
        p.parseheaders(header) should throwA[BadRequest]
      }

      "throw an error if the request line is too long" in {

        val p = new Parser(maxReq = request.length - 1)
        p.parseLine(request) should throwA[BadRequest]
      }

    }
  }
}



