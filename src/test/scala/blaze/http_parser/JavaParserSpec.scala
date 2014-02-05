package blaze
package http_parser

import org.scalatest.{Matchers, WordSpec}
import java.nio.ByteBuffer
import http_parser.HttpTokens.EndOfContent
import blaze.http_parser.BaseExceptions.BadRequest
import scala.collection.mutable.ListBuffer

/**
 * @author Bryce Anderson
 *         Created on 1/2/14
 */
class JavaParserSpec extends WordSpec with Matchers {

  implicit def strToBuffer(str: String) = ByteBuffer.wrap(str.getBytes())



  class Parser(maxReq: Int = 1034, maxHeader: Int = 1024) extends Http1ServerParser(maxReq, maxHeader, 1) {

    val sb = new StringBuilder

    val h = new ListBuffer[(String, String)]

    def parseLine(s: ByteBuffer) = parseRequestLine(s)

    def parseheaders(s: ByteBuffer): Boolean = parseHeaders(s)

    def parsecontent(s: ByteBuffer): ByteBuffer = {
      val c = super.parseContent(s)
      if (c != null) {
        c.mark()
        while(c.hasRemaining) sb.append(c.get().toChar)
        c.reset()
      }
      c
    }

    def submitRequestLine(methodString: String, uri: String, scheme: String, majorversion: Int, minorversion: Int) {
//      println(s"$methodString, $uri, $scheme/$majorversion.$minorversion")
    }

    def headerComplete(name: String, value: String) {
      //println(s"Found header: '$name': '$value'")
      h += ((name, value))
    }
  }

  def toChunk(str: String): String = {
    val len = Integer.toHexString(str.length) + "\r\n"
    len + 
      str + 
      "\r\n"
  }

  val l_headers = ("From", "someuser@jmarshall.com  ")::
                  ("HOST", "www.foo.com")::
                  ("User-Agent", "HTTPTool/1.0  ")::
                  ("Some-Header", "")::Nil


  val request = "POST /enlighten/calais.asmx HTTP/1.1\r\n"
  val host =    "HOST: www.foo.com\r\n"

  val http10 = "GET /path/file.html HTTP/1.0\r\n"

//  val header =  "From: someuser@jmarshall.com  \r\n" +
//                "HOST: www.foo.com\r\n" +
//                "User-Agent: HTTPTool/1.0  \r\n" +
//                "Some-Header\r\n" +
//                "\r\n"
  val header = l_headers.foldLeft(new StringBuilder){ (sb, h) =>
                sb.append(h._1)
                if (h._2.length > 0) sb.append(": " + h._2)
                sb.append("\r\n")
              }.append("\r\n").result

  val body    = "hello world"

  val lengthh = s"Content-Length: ${body.length}\r\n"
  
  val chunked = "Transfer-Encoding: chunked\r\n"

  val mockFiniteLength = request + host + lengthh + header + body

  val mockChunked = request + host + chunked + header + toChunk(body) + toChunk(body + " again!") + "0 \r\n" + "\r\n"

  val twoline = request + host

  "Http1ServerParser" should {
    "Parse the request line for HTTP" in {
      val p = new Parser()
      p.parseLine("POST /enlighten/calais.asmx HTTP/1.1\r\n") should equal(true)

//      p.s should equal ("Request('POST', '/enlighten/calais.asmx', 'http', 1.1)")
//      p.getState() should equal (ParserState.Idle)
    }

    "Parse the request line for HTTP in segments" in {
      val p = new Parser()
      p.parseLine("POST /enlighten/cala") should equal(false)
      p.parseLine("is.asmx HTTP/1.1\r\n") should equal(true)

      //      p.s should equal ("Request('POST', '/enlighten/calais.asmx', 'http', 1.1)")
      //      p.getState() should equal (ParserState.Idle)
    }


    "Parse the request line for HTTPS" in {
      val p = new Parser()
      p.parseLine("POST /enlighten/calais.asmx HTTPS/1.1\r\n") should equal(true)
    }

    "Parse headers" in {
      val p = new Parser()
      p.parseheaders(header) should equal (true)
      p.getContentType should equal (EndOfContent.UNKNOWN_CONTENT)
      p.h.result should equal(l_headers.map{ case (a, b) => (a.trim, b.trim)})
    }

    "need input on partial headers" in {
      val p = new Parser()
      p.parseHeaders(header.slice(0, 20)) should equal (false)
      p.parseheaders(header.substring(20)) should equal (true)
      p.h.result should equal(l_headers.map{ case (a, b) => (a.trim, b.trim)})
    }

    "Parse a full request" in {
      val p = new Parser()
      val b = ByteBuffer.wrap(mockFiniteLength.getBytes())

      p.parseLine(b) should equal(true)

      p.parseheaders(b) should equal(true)

      p.sb.result() should equal ("")

      p.parsecontent(b) should not equal(null)
      p.sb.result() should equal(body)
      p.contentComplete() should equal(true)


      p.reset()
      p.requestLineComplete() should equal (false)
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
        p.parsecontent(b) should not equal (null)
        if (b.limit() < blim) b.limit(b.limit() + 1)
      }

      p.sb.result() should equal(body)

      p.reset()
      p.requestLineComplete() should equal (false)
    }

    "Parse a chunked request" in {
      val p = new Parser()
      val b = ByteBuffer.wrap(mockChunked.getBytes())

      p.parseLine(b) should equal(true)

      p.parseheaders(b) should equal(true)
      p.sb.result() should equal ("")

      p.parsecontent(b) should not equal(null)
      p.parsecontent(b) should not equal(null)
      // two real messages
      p.parsecontent(b) should equal(null)
      p.sb.result() should equal(body + body + " again!")

      p.reset()
    }

    "Parse a chunked request with trailers" in {
      val p = new Parser()
      val req = mockChunked.substring(0, mockChunked.length - 2) + "Foo\r\n\r\n"
      val b = ByteBuffer.wrap(req.getBytes())

      println(mockChunked)

      p.parseLine(b) should equal(true)

      p.parseheaders(b) should equal(true)
      p.sb.result() should equal ("")
      p.h.clear()

      p.parsecontent(b) should not equal(null)
      p.parsecontent(b) should not equal(null)
      // two real messages
      p.parsecontent(b) should equal(null)
      p.h.result should equal(("Foo","")::Nil)
      p.sb.result() should equal(body + body + " again!")

      p.reset()
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

      p.contentComplete() should equal (false)

      while (!p.contentComplete()) {
        p.parsecontent(b)
        if (b.limit < blim) b.limit(b.limit() + 1)
      }

      p.contentComplete() should equal (true)
      p.sb.result() should equal(body + body + " again!")
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

      p.contentComplete() should equal (false)

      while (!p.contentComplete()) {
        p.parsecontent(b)
        if (b.limit < blim) b.limit(b.limit() + 1)
      }
      p.h.result should equal(("Foo","")::Nil)
      p.contentComplete() should equal (true)
      p.sb.result() should equal(body + body + " again!")
    }

    "throw an error if the headers are too long" in {
      val header =  "From: someuser@jmarshall.com  \r\n" +
        "HOST: www.foo.com\r\n" +
        "User-Agent: HTTPTool/1.0  \r\n" +
        "Some-Header\r\n"

      val p = new Parser(maxHeader = header.length - 1)
      an [BadRequest] should be thrownBy p.parseheaders(header)
    }

    "throw an error if the request line is too long" in {

      val p = new Parser(maxReq = request.length - 1)
      an [BadRequest] should be thrownBy p.parseLine(request)
    }
  }
}

