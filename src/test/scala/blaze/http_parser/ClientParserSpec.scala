package blaze.http_parser

import org.scalatest.{Matchers, WordSpec}
import java.nio.charset.StandardCharsets.US_ASCII
import scala.collection.mutable.ListBuffer
import java.nio.ByteBuffer

/**
 * @author Bryce Anderson
 *         Created on 2/4/14
 */
class ClientParserSpec extends WordSpec with Matchers {

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
    "Parse the response line" in {
      val p = new TestParser

      p.parseResponse(wrap(resp.getBytes(US_ASCII))) should equal (true)
      p.responseLineComplete() should equal (true)
      p.code should equal(200)
      p.reason should equal("OK")
      p.majorversion should equal(1)
      p.minorversion should equal(1)

      p.mayHaveBody() should equal(true)
    }

    "Parse headers" in {
      val p = new TestParser
      val msg = resp + l_headersstr

      //println(msg.replace("\r\n", "\\r\\n\r\n"))

      val bts = wrap(msg.getBytes(US_ASCII))
      p.parseResponse(bts) should equal (true)
      p.responseLineComplete() should equal (true)

      p.parseheaders(bts) should equal (true)
      p.headersComplete() should equal (true)

      val stripedh = l_headers.map{ case (a, b) => (a.trim, b.trim) }
      p.headers.foldLeft(true){ (a, b) => a && stripedh.contains(b) } should equal(true)
    }
    
    "Parse a body with defined length" in {
      val p = new TestParser
      val full = resp + content_length + l_headersstr + body

//      println(full.replace("\r\n", "\\r\\n\r\n"))

      val bts = wrap(full.getBytes(US_ASCII))

      p.parseResponse(bts) should equal (true)
      p.parseheaders(bts) should equal (true)

      p.contentComplete() should equal(false)

      val out = p.parsebody(bts)
      out.remaining() should equal(body.length)

      US_ASCII.decode(out).toString should equal(body)
    }

    "Parse a chunked body" in {

      val p = new TestParser
      val full = resp + "Transfer-Encoding: chunked\r\n" + l_headersstr +
                  Integer.toHexString(body.length) + "\r\n" +
                  body + "\r\n" +
                  "0\r\n" +
                  "\r\n"

      //      println(full.replace("\r\n", "\\r\\n\r\n"))

      val bts = wrap(full.getBytes(US_ASCII))

      p.parseResponse(bts) should equal (true)
      p.parseheaders(bts) should equal (true)

      p.contentComplete() should equal(false)

      val out = p.parsebody(bts)
      out.remaining() should equal(body.length)

      p.contentComplete() should equal(false)
      p.parsebody(bts).remaining() should equal(0)
      p.contentComplete() should equal(true)

      US_ASCII.decode(out).toString should equal(body)

    }

  }

}
