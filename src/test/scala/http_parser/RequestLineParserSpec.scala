package http_parser

import org.scalatest.{Matchers, WordSpec}

/**
 * @author Bryce Anderson
 *         Created on 12/31/13
 */
class RequestLineParserSpec extends WordSpec with Matchers {

  class DefaultRequestMethodParser(protected val input: String) extends ParserTools(100000)
  with RequestLineParser
  with StringParserTools {

    def maxStatusLine: Int = Integer.MAX_VALUE

    var s = ""

    def handleLine(method: String, url: String, scheme: String, major: Int, minor: Int): Boolean = {
      s = s"Request('$method', '$url', '$scheme', $major.$minor)"
      true
    }
  }


  val request = "POST /enlighten/calais.asmx HTTP/1.1\r\n"
  val host =    "HOST: www.foo.com\r\n"

  val http10 = "GET /path/file.html HTTP/1.0\r\n"

  val headers = "From: someuser@jmarshall.com\r\n" +
                "User-Agent: HTTPTool/1.0\r\n" +
                "Some-Header\r\n" +
                "\r\n"

  val mock = request + headers

  val twoline = request + host

  case class TestTools(val input: String) extends ParserTools(1000) with StringParserTools {
    def readline: String = {
      loadLine(true)
      val s = result
      clearBuffer()
      s
    }

    def readUntil0(char: Char, keep: Boolean) = readUntil(char, keep)
  }

  "ParserTools" should {

    "Return a line" in {
      TestTools(request).readline should equal(request.split('\r')(0))
    }

    "load lines sequentially" in {
      val t = TestTools(twoline)
      t.readline should equal(request.split('\r')(0))
      t.readline should equal(host.split('\r')(0))
    }

    "read until" in {
      val t = TestTools(request)
      t.readUntil0(' ', false) should equal(true)
      t.result() should equal("POST")
      t.clearBuffer()

      t.readline should equal("/enlighten/calais.asmx HTTP/1.1")
      t.readline should equal ("")
      t.getState() should equal(ParserState.NeedsInput)

    }
  }

  "StatusLineParsers" should {
    "Parse the request line for HTTP" in {
      val p = new DefaultRequestMethodParser("POST /enlighten/calais.asmx HTTP/1.1\r\n")
      p.getState() should equal (ParserState.Idle)
      p.parseRequestLine() should equal(true)
      p.s should equal ("Request('POST', '/enlighten/calais.asmx', 'http', 1.1)")
      p.getState() should equal (ParserState.Idle)
    }

    "Parse the request line for HTTPS" in {
      val p = new DefaultRequestMethodParser("POST /enlighten/calais.asmx HTTPS/1.1\r\n")
      p.getState() should equal (ParserState.Idle)
      p.parseRequestLine() should equal(true)
      p.s should equal ("Request('POST', '/enlighten/calais.asmx', 'https', 1.1)")
      p.getState() should equal (ParserState.Idle)
    }

    "Parse headers" in {
      val p = new DefaultRequestMethodParser(headers)
      p.parseHeaders() should equal (true)

      println(p.getHeaders)
      println(p.getState())
    }

    "need input on partial headers" in {
      val p = new DefaultRequestMethodParser(headers.slice(0, 20))
      p.parseHeaders() should equal (false)
      p.getState() should equal (ParserState.NeedsInput)

//      println(p.getState())
//      println(p.getHeaders)
    }
  }

}
