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

  val resp = "HTTP/1.1 200 OK\r\n".getBytes(US_ASCII)
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

    def headerComplete(name: String, value: String) {
      headers += ((name, value))
    }
  }

  "Client parser" should {
    "Parse the response line" in {
      val p = new TestParser
      p.parseResponse(wrap(resp)) should equal (true)
      p.code should equal(200)
      p.reason should equal("OK")
      p.majorversion should equal(1)
      p.minorversion should equal(1)
    }

  }

}
