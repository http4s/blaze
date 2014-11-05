package org.http4s.blaze.pipeline
package stages


import org.specs2.mutable._
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import org.specs2.time.NoTimeConversions

class SerializingStageSpec extends Specification with NoTimeConversions {

  class SlowIntHead extends SlowHead[Int] {

    val ints = new ListBuffer[Int]

    val i = new AtomicInteger(0)
    def get: Int = i.getAndIncrement

    def write(data: Int): Unit = {
      ints += data
    }

    def name: String = "SlowIntHead"
  }

  class Nameless extends TailStage[Int] {
    def name: String = "int getter"
  }

  "SerializingStage" should {

    val tail = new Nameless
    val head = new SlowIntHead

    // build our pipeline
    LeafBuilder(tail).prepend(new SerializingStage[Int]).base(head)

    val ints = (0 until 200).toList

    "serialize reads" in {
      val tail = new Nameless
      val head = new SlowIntHead

      // build our pipeline
      LeafBuilder(tail).prepend(new SerializingStage[Int]).base(head)


      val results = ints map { i =>
        tail.channelRead()
      }

      val numbers = Future.sequence(results)
      Await.result(numbers, 10.seconds) should_== ints
    }

    "serialize writes" in {
      val f = (0 until 100).map{ i =>
        tail.channelWrite(i)
      }.last

      val f2 = f.flatMap{ _ =>
        (100 until 200 by 2).map{ j =>
          tail.channelWrite(List(j, j+1))
        }.last
      }

      Await.result(f2, 20.seconds)
      head.ints.result() should_== ints
    }
  }

}
