package http_parser

import org.scalatest.{Matchers, WordSpec}
import http_parser.ParserRoot.{State, RequestHandler}
import java.nio.ByteBuffer
import http_parser.HttpTokens.EndOfContent
import http_parser.BaseExceptions.NeedsInput

/**
 * @author Bryce Anderson
 *         Created on 1/2/14
 */
class JavaParserSpec extends WordSpec with Matchers {

  val headers = collection.mutable.ListBuffer.empty[(String, String)]

  val handler = new RequestHandler[ByteBuffer] {
    def badMessage(status: Int, reason: String) {
      sys.error(s"Received bad message: $status, $reason")
    }

    def earlyEOF() {
      sys.error("Early EOF")
    }

    def content(item: ByteBuffer): Boolean = ???

    def headerComplete(): Boolean = ???

    def messageComplete(): Boolean = ???

    def parsedHeader(name: String, value: String): Boolean = {
      println(s"Parsed header: '$name', '$value'")
      headers += ((name, value))
      true
    }

    def getHeaderCacheSize: Int = ???

    def startRequest(methodString: String, uri: String, scheme: String, majorversion: Int, minorversion: Int): Boolean = {
      println(s"$methodString, $uri, $scheme/$majorversion.$minorversion")
      true
    }
  }

  class Parser extends ParserRoot(handler, 1024, 1024, 1) {
    def parseLine(s: String) = parseRequestLine(ByteBuffer.wrap(s.getBytes))

    def state(state: State): Unit = super.setState(state)

    def parseHeaders(s: String): Boolean = parseHeaders(ByteBuffer.wrap(s.getBytes))
  }


  val request = "POST /enlighten/calais.asmx HTTP/1.1\r\n"
  val host =    "HOST: www.foo.com\r\n"

  val http10 = "GET /path/file.html HTTP/1.0\r\n"

  val header =  "From: someuser@jmarshall.com  \r\n" +
                "HOST: www.foo.com\r\n" +
                "User-Agent: HTTPTool/1.0  \r\n" +
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

  "ParserRoot" should {
    "Parse the request line for HTTP" in {
      val p = new Parser()
      p.parseLine("POST /enlighten/calais.asmx HTTP/1.1\r\n") should equal(true)

//      p.s should equal ("Request('POST', '/enlighten/calais.asmx', 'http', 1.1)")
//      p.getState() should equal (ParserState.Idle)
    }

    "Parse the request line for HTTP in segments" in {
      val p = new Parser()
      a [NeedsInput] should be thrownBy p.parseLine("POST /enlighten/cala")
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
      p.state(State.HEADER_IN_NAME)
      p.parseHeaders(header) should equal (true)
      p.getContentType should equal (EndOfContent.UNKNOWN_CONTENT)
    }

    "need input on partial headers" in {
      val p = new Parser()
      p.state(State.HEADER_IN_NAME)
      a [NeedsInput] should be thrownBy p.parseHeaders(header.slice(0, 20))
      p.parseHeaders(header.substring(20)) should equal (true)

    }
  }

}

