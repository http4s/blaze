package http_parser

import org.scalameter.api._
import scala.util.control.NoStackTrace

object Main {

  def main(args: Array[String]) {
    println("hello world")
  }
}

object RangeBenchmark
  extends PerformanceTest.Quickbenchmark {
  val sizes = Gen.range("size")(0, 100000, 10000)

  val ranges = for {
    size <- sizes
  } yield size

  performance of "Throws" in {
    measure method "myFun" in {

      val t = new Throws

      using(ranges) in { r =>
        t.run(r)
      }
    }
  }
}

object FooException extends Exception with NoStackTrace

class Throws {

  def myFun(i: Int): Int = {
    if (i > 100000) throw FooException
    i
  }

  def run(i: Int) {
    var j = i
    var jj = 0
    while (jj < 1000000000) {
      while (j < i + 100000) {
        try myFun(j)
        catch { case FooException => /* NOOP */ }
        j += 1
      }
      jj += 1
    }


  }
}

  