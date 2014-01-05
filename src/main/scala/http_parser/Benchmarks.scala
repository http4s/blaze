package http_parser

import java.nio.ByteBuffer
import scala.annotation.tailrec
import http_parser.RequestParser.State

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */
class Benchmarks {

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

  def toChunk(str: String): String = {
    val len = Integer.toHexString(str.length) + "\r\n"
    len +
      str +
      "\r\n"
  }

  def run(i: Int)(f: Int => Any) {

    // Do a warmup
    var ii = math.min(i, 100000)
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
    println(s"Parsed ${i/(end - start)}K req/sec")

  }
  
  def checkingBenchmark() {
    val p = new BenchParser()
    val b = ByteBuffer.wrap(mockChunked.getBytes())
    val blim = b.limit()

    def iteration(remaining: Int)  {
      b.position(0)

      if (remaining % 500000 == 0) println(s"Iteration $remaining")

      b.limit(blim - 20)

      p.parseLine(b)// should equal(true)
      assert(p.getState == State.HEADER)

      p.parseheaders(b) //should equal(true)
      assert(p.getState == State.CONTENT)

      assert(p.parsecontent(b))
      //        p.getState should equal (State.CONTENT)

      b.limit(blim - 10)
      assert(p.parsecontent(b))

      b.limit(blim)
      assert(p.parsecontent(b))

      p.reset()

      assert(p.getState == State.START)
    }

    run(1000000)(iteration(_))

    println(b.position(0))
  }
  
  def rawBenchmark() {
    val p = new BenchParser()
    val b = ByteBuffer.wrap(mockChunked.getBytes())

    def iteration(remaining: Int): Unit = if (remaining > 0) {
      b.position(0)

      if (remaining % 500000 == 0) println(s"Iteration $remaining")

      assert(p.parseLine(b))

      assert(p.parseheaders(b)) 

      assert(p.parsecontent(b))

      p.reset()

      assert(p.getState == State.START)
    }

    run(1000000)(iteration(_))

    println(b.position(0))
  }

}

object Benchmarks {
  def main(args: Array[String]) {
    val b = new Benchmarks
    b.checkingBenchmark()
    b.rawBenchmark()
  }
}
