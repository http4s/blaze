package blaze
package http_parser

import java.nio.ByteBuffer
import http_parser.RequestParser.State
import scala.collection.mutable.ListBuffer

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */
class Benchmarks {

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
    val p = new BenchParser() {
      val sb = new StringBuilder

      override def submitContent(buffer: ByteBuffer): Boolean = {
        while(buffer.hasRemaining)
          sb.append(buffer.get().toChar)
        true
      }
    }
    val b = ByteBuffer.wrap(mockChunked.getBytes())
    val blim = b.limit()
    val reconstructed = body + ", " + body + " again!"

    def iteration(remaining: Int)  {
      b.position(0)

      if (remaining % 250000 == 0) println(s"Iteration $remaining")

      b.limit(blim - 20)

      p.parseLine(b)// should equal(true)
      assert(p.getState == State.HEADER)

      p.parseheaders(b) //should equal(true)
      assert(p.getState == State.CONTENT)

      assert(p.parsecontent(b))
      assert(p.getState == State.CONTENT)

      b.limit(blim - 10)
      assert(p.parsecontent(b))

      b.limit(blim)
      assert(p.parsecontent(b))
      assert(p.getState == State.END)
      assert(p.sb.result() == reconstructed)

      p.sb.clear()
      p.reset()

      assert(p.getState == State.START)
    }

    run(1000000)(iteration(_))
  }
  
  def rawBenchmark() {
    val p = new BenchParser()
    val b = ByteBuffer.wrap(mockChunked.getBytes())

    def iteration(remaining: Int): Unit = if (remaining > 0) {
      b.position(0)

      if (remaining % 250000 == 0) println(s"Iteration $remaining")

      assert(p.parseLine(b))

      assert(p.parseheaders(b)) 

      assert(p.parsecontent(b))

      p.reset()

      assert(p.getState == State.START)
    }

    run(1000000)(iteration(_))
  }

  def headerCounterBenchmark() {
    val p = new BenchParser() {
      val headers = new ListBuffer[(String, String)]

      override def headerComplete(name: String, value: String): Unit = {
        headers += ((name, value))
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
      assert(p.parsecontent(b))
      assert(p.headers.length == 5)
      p.clear()
      assert(p.getState == State.START)
    }

    run(10)(iteration(_))

  }

}

object Benchmarks {
  def main(args: Array[String]) {
    val b = new Benchmarks
    b.headerCounterBenchmark()
    b.checkingBenchmark()
    b.rawBenchmark()
  }
}
