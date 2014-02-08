package blaze.http_parser

import java.nio.ByteBuffer
import scala.collection.mutable.ListBuffer
import org.scalatest.WordSpec

/**
 * @author Bryce Anderson
 *         Created on 2/8/14
 */
class Benchmarks extends WordSpec {

  val request = "POST /enlighten/calais.asmx HTTP/1.1\r\n"

  val header =  "From: someuser@jmarshall.com  \r\n" +
    "HOST: www.foo.com\r\n" +
    "User-Agent: HTTPTool/1.0  \r\n" +
    "Some-Header\r\n" +
    "\r\n"

  val body    = "hello world"
  val chunked = "Transfer-Encoding: chunked\r\n"

  val mockChunked = request + chunked + header + toChunk(body) + toChunk(", " + body + " again!") + "0 \r\n" + "\r\n"

  def toChunk(str: String): String = {
    val len = Integer.toHexString(str.length) + "\r\n"
    len +
      str +
      "\r\n"
  }

  def run(i: Int)(f: Int => Any) {

    // Do a warmup
    var ii = math.min(i, 1000000)
    while (ii > 0) {
      ii -= 1
      f(ii)
    }

    ii = i
    val start = System.currentTimeMillis()

    while (ii > 0) {
      ii -= 1
      f(ii)
    }
    val end = System.currentTimeMillis()

    if (end - start > 0) println(s"Parsed ${i/(end - start)}K req/sec")
    else println("Result to fast to give accurate performance measurement")
  }

  def checkingBenchmark(iterations: Int) {
    val p = new BenchParser() {
      val sb = new StringBuilder

      override def parsecontent(s: ByteBuffer): ByteBuffer = {
        val b = super.parsecontent(s)
        if (b != null) {
          b.mark()
          while (b.hasRemaining) sb.append(b.get().toChar)
          b.reset()
        }
        b
      }

      override def headerComplete(name: String, value: String): Boolean = {
        //println(s"Header($name, $value)")
        super.headerComplete(name, value)
      }

      //      override def submitRequestLine(methodString: String, uri: String, scheme: String, majorversion: Int, minorversion: Int): Unit = {
      //        println(s"Request($methodString, $uri, $scheme/$majorversion.$minorversion)")
      //        super.submitRequestLine(methodString, uri, scheme, majorversion, minorversion)
      //      }
    }


    val b = ByteBuffer.wrap(mockChunked.getBytes())
    val blim = b.limit()
    val reconstructed = body + ", " + body + " again!"

    def iteration(remaining: Int)  {
      b.position(0)

      if (remaining % 250000 == 0) println(s"Iteration $remaining")

      b.limit(blim - 20)


      assert(p.parseLine(b))// should equal(true)
      assert(p.requestLineComplete())

      p.parseheaders(b) //should equal(true)
      assert(p.headersComplete())

      p.parsecontent(b)
      assert(!p.contentComplete())

      b.limit(blim - 10)
      p.parsecontent(b)

      b.limit(blim)
      p.parsecontent(b)
      p.parsecontent(b)
      assert(p.contentComplete())
      //      println(p.sb.result())
      assert(p.sb.result() == reconstructed)

      p.sb.clear()
      p.reset()

      assert(!p.requestLineComplete())
    }

    run(iterations)(iteration(_))
  }

  def rawBenchmark(iterations: Int) {
    val p = new BenchParser()
    val b = ByteBuffer.wrap(mockChunked.getBytes())

    def iteration(remaining: Int): Unit = if (remaining > 0) {
      b.position(0)

      if (remaining % 250000 == 0) println(s"Iteration $remaining")

      assert(p.parseLine(b))

      assert(p.parseheaders(b))

      assert(p.parsecontent(b) != null)
      assert(p.parsecontent(b) != null)
      assert(p.parsecontent(b).remaining() == 0 && p.contentComplete())

      p.reset()

      assert(!p.requestLineComplete())
    }

    run(iterations)(iteration(_))
  }

  def headerCounterBenchmark(iterations: Int) {
    val p = new BenchParser() {
      val headers = new ListBuffer[(String, String)]

      override def headerComplete(name: String, value: String): Boolean = {
        headers += ((name, value))
        false
      }

      def clear() {
        headers.clear()
        super.reset()
      }
    }
    val b = ByteBuffer.wrap(mockChunked.getBytes())

    def iteration(remaining: Int): Unit = if (remaining > 0) {
      b.position(0)

      assert(p.parseLine(b))
      assert(p.parseheaders(b))
      p.parsecontent(b)
      assert(p.headers.length == 5)
      p.clear()
      assert(!p.requestLineComplete())
    }

    run(iterations)(iteration(_))

  }

  "Benchmark" should {
    "work" in {
      checkingBenchmark(3)
      rawBenchmark(3)
      headerCounterBenchmark(3)
    }
  }

}