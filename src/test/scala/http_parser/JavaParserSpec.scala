package http_parser

import org.scalatest.{Matchers, WordSpec}
import http_parser.RequestParser.State
import java.nio.ByteBuffer
import http_parser.HttpTokens.EndOfContent

/**
 * @author Bryce Anderson
 *         Created on 1/2/14
 */
class JavaParserSpec extends WordSpec with Matchers {

  implicit def strToBuffer(str: String) = ByteBuffer.wrap(str.getBytes())

  class BenchParser(maxReq: Int = 1034, maxHeader: Int = 1024) extends RequestParser(maxReq, maxHeader, 1) {

    val sb = new StringBuilder

    def parseLine(s: ByteBuffer) = parseRequestLine(s)

    def state(state: State): Unit = super.setState(state)

    def parseheaders(s: ByteBuffer): Boolean = parseHeaders(s)

    def parsecontent(s: ByteBuffer): Boolean = parseContent(s)

    def badMessage(status: Int, reason: String) {
      sys.error(s"Bad message: $status, $reason")
    }

    def earlyEOF() {}

    def startRequest(methodString: String,
                     uri: String,
                     scheme: String,
                     majorversion: Int,
                     minorversion: Int): Boolean = true

    def submitContent(buffer: ByteBuffer): Boolean = {
      while (buffer.hasRemaining) sb.append(buffer.get().toChar)
      true
    }

    def headersComplete() {}

    def requestComplete() { }

    def headerComplete(name: String, value: String) { }
  }

  class Parser(maxReq: Int = 1034, maxHeader: Int = 1024) extends RequestParser(maxReq, maxHeader, 1) {

    val sb = new StringBuilder

    def parseLine(s: ByteBuffer) = parseRequestLine(s)

    def state(state: State): Unit = super.setState(state)

    def parseheaders(s: ByteBuffer): Boolean = parseHeaders(s)

    def parsecontent(s: ByteBuffer): Boolean = parseContent(s)

    def badMessage(status: Int, reason: String) {
      sys.error(s"Bad message: $status, $reason")
    }

    def earlyEOF() {}

    def startRequest(methodString: String, uri: String, scheme: String, majorversion: Int, minorversion: Int): Boolean = {
      println(s"$methodString, $uri, $scheme/$majorversion.$minorversion")
      true
    }

    def submitContent(buffer: ByteBuffer): Boolean = {
      //println("Appending buffer: " + buffer)
      while (buffer.hasRemaining) sb.append(buffer.get().toChar)
      true
    }

    def headersComplete() {}

    def requestComplete() {
      println("Request complete.")
    }

    def headerComplete(name: String, value: String) {
      //println(s"Found header: '$name': '$value'")
    }
  }

  def toChunk(str: String): String = {
    val len = Integer.toHexString(str.length) + "\r\n"
    len + 
      str + 
      "\r\n"
  }


  val request = "POST /enlighten/calais.asmx HTTP/1.1\r\n"
  val host =    "HOST: www.foo.com\r\n"

  val http10 = "GET /path/file.html HTTP/1.0\r\n"

  val header =  "From: someuser@jmarshall.com  \r\n" +
                "HOST: www.foo.com\r\n" +
                "User-Agent: HTTPTool/1.0  \r\n" +
                "Some-Header\r\n" +
                "\r\n"

  val body    = "hello world"

  val lengthh = s"Content-Length: ${body.length}\r\n"
  
  val chunked = "Transfer-Encoding: chunked\r\n"

  val mockFiniteLength = request + host + lengthh + header + body

  val mockChunked = request + host + chunked + header + toChunk(body) + toChunk(body + " again!") + "0 \r\n" + "\r\n"

  val twoline = request + host

  "RequestParser" should {
    "Parse the request line for HTTP" in {
      val p = new Parser()
      p.parseLine("POST /enlighten/calais.asmx HTTP/1.1\r\n") should equal(true)

//      p.s should equal ("Request('POST', '/enlighten/calais.asmx', 'http', 1.1)")
//      p.getState() should equal (ParserState.Idle)
    }

    "Parse the request line for HTTP in segments" in {
      val p = new Parser()
      p.parseLine("POST /enlighten/cala") should equal(true)
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
    }

    "need input on partial headers" in {
      val p = new Parser()
      p.parseHeaders(header.slice(0, 20)) should equal (true)
      p.parseheaders(header.substring(20)) should equal (true)

    }

    "Parse a full request" in {
      val p = new Parser()
      val b = ByteBuffer.wrap(mockFiniteLength.getBytes())

      p.parseLine(b) should equal(true)
      p.getState should equal (State.HEADER)
      
      p.parseheaders(b) should equal(true)
      p.getState should equal (State.CONTENT)

      p.sb.result() should equal ("")

      p.parsecontent(b) should equal(true)
      p.getState should equal (State.END)
      p.sb.result() should equal(body)

      p.reset()
      p.getState should equal(State.START)
    }

    "Give parse a full request in fragments" in {
      val p = new Parser()
      val b = ByteBuffer.wrap(mockFiniteLength.getBytes())

      val blim = b.limit()

      b.limit(1)

      while (p.inRequestLine()) {
        p.parseLine(b) should equal(true)
        b.limit(b.limit() + 1)

      }

      while (p.inHeaders()) {
        p.parseheaders(b) should equal(true)
        b.limit(b.limit() + 1)
      }

      while (p.inContent()) {
        p.parsecontent(b) should equal (true)
        if (b.limit() < blim) b.limit(b.limit() + 1)
      }

      p.getState should equal (State.END)

      p.finished() should equal (true)

      p.sb.result() should equal(body)

      p.reset()
      p.getState should equal(State.START)
    }

    "Give parse a chunked request" in {
      val p = new Parser()
      val b = ByteBuffer.wrap(mockChunked.getBytes())

      println(mockChunked)

      p.parseLine(b) should equal(true)
      p.getState should equal (State.HEADER)

      p.parseheaders(b) should equal(true)
      p.getState should equal (State.CONTENT)

      p.sb.result() should equal ("")

      p.parsecontent(b) should equal(true)
      p.getState should equal (State.END)
      p.sb.result() should equal(body + body + " again!")

      p.reset()
      p.getState should equal(State.START)
    }

    "Give parse a chunked request in fragments" in {
      val p = new Parser()
      val b = ByteBuffer.wrap(mockChunked.getBytes())
      val blim = b.limit()

      // Do it one char at a time /////////////////////////////////////////
      b.limit(1)
      b.position(0)
      p.sb.clear()


      while (p.inRequestLine()) {
        p.parseLine(b) should equal (true)
        b.limit(b.limit() + 1)
      }

      p.getState should equal (State.HEADER)

      while (p.inHeaders()) {
        p.parseheaders(b) should equal (true)
        b.limit(b.limit() + 1)
      }

      println(p.getState)
      p.getState should equal (State.CONTENT)

      while (p.inContent()) {
        p.parsecontent(b) should equal (true)
        if (b.limit < blim) b.limit(b.limit() + 1)
      }

      println("Got here")

      p.getState should equal (State.END)
      p.finished() should equal (true)
      p.sb.result() should equal(body + body + " again!")
    }

    "Benchmark" in {

      val p = new BenchParser()
      val b = ByteBuffer.wrap(mockChunked.getBytes())
      val result = body + body + " again!"

      val blim = b.limit()

      def go(remaining: Int): Unit = if (remaining > 0) {
        b.position(0)

        if (remaining % 500000 == 0) println(s"Iteration $remaining")

        b.limit(blim - 20)

        p.parseLine(b)// should equal(true)
//        p.getState should equal (State.HEADER)

        p.parseheaders(b) //should equal(true)
//        p.getState should equal (State.CONTENT)

//        p.sb.result() should equal ("")

        p.parsecontent(b) should equal(true)
//        p.getState should equal (State.CONTENT)

        b.limit(blim - 10)
        p.parsecontent(b)// should equal(true)
//        p.getState should equal (State.CONTENT)

        b.limit(blim)
        p.parsecontent(b) //should equal(true)
//        p.getState should equal (State.END)
//        p.sb.result() should equal(result)

        p.reset()
//        p.sb.clear()

//        p.getState should equal(State.START)

        go(remaining - 1)
      }

      val reps = 1000000
      go(reps)

      val start = System.currentTimeMillis()
      go (reps)
      val end = System.currentTimeMillis()

      println(s"Parsed ${reps*1000/(end - start)} req/sec")

      println(b.position(0))
    }

    "Bare Benchmark" in {
      val p = new BenchParser() {
        override def submitContent(buffer: ByteBuffer): Boolean = {
          buffer.position(buffer.limit())
          true
        }
      }
      val b = ByteBuffer.wrap(mockChunked.getBytes())
//      val result = body + body + " again!"

//      val blim = b.limit()

      def go(remaining: Int): Unit = if (remaining > 0) {
        b.position(0)

        if (remaining % 500000 == 0) println(s"Iteration $remaining")

//        b.limit(blim - 20)

        p.parseLine(b) //should equal(true)
//        p.getState should equal (State.HEADER)

        p.parseheaders(b) //should equal(true)
//        p.getState should equal (State.CONTENT)

//        p.sb.result() should equal ("")

        p.parsecontent(b) //should equal(true)
//        p.getState should equal (State.CONTENT)

//        b.limit(blim - 10)
//        p.parsecontent(b) should equal(true)
//        p.getState should equal (State.CONTENT)
//
//        b.limit(blim)
//        p.parsecontent(b) should equal(true)
//        p.getState should equal (State.END)
//        p.sb.result() should equal(result)

        p.reset()
        p.sb.clear()

//        p.getState //should equal(State.START)

        go(remaining - 1)
      }

      val reps = 1000000
      go(reps)

      val start = System.currentTimeMillis()
      go (reps)
      val end = System.currentTimeMillis()

      println(s"Parsed ${reps*1000/(end - start)} req/sec")

      println(b.position(0))
    }
  }

}

