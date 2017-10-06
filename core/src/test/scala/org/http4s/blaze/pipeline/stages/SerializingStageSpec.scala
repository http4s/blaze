package org.http4s.blaze.pipeline
package stages

import org.http4s.blaze.util.Execution
import org.specs2.mutable._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.Await

class SerializingStageSpec extends Specification {

  // It's critical to use a thread-local execution context to ensure that
  // race conditions aren't happening between the data feeding into and
  // out of the SlowHead and whatever thread the continuations are being
  // completed on.
  private implicit val ec = Execution.trampoline

  class Nameless extends TailStage[Int] {
    def name: String = "int getter"
  }

  "SerializingStage" should {

    val ints = (0 until 200).toList

    "serialize reads" in {
      val tail = new Nameless
      val head = new SlowHead[Int]

      // build our pipeline
      LeafBuilder(tail).prepend(new SerializingStage[Int]).base(head)

      val results = ints.map { _ =>
        tail.channelRead()
      }

      val numbers = Future.sequence(results)

      ints.foreach { i =>
        val p = head.takeRead
        p.success(i)
      }

      Await.result(numbers, 10.seconds) should_== ints
    }

    "serialize writes" in {
      val tail = new Nameless
      val head = new SlowHead[Int]

      // build our pipeline
      LeafBuilder(tail).prepend(new SerializingStage[Int]).base(head)

      val f = (0 until 100).map { i =>
        tail.channelWrite(i)
      }.last

      val f2 = f.flatMap { _ =>
        (100 until 200 by 2).map { j =>
          tail.channelWrite(List(j, j+1))
        }.last
      }

      val writes = ints.map { _ =>
        val write = head.takeWrite
        write.completion.success(())
        write.value
      }

      Await.result(f2, 20.seconds)
      writes should_== ints
    }
  }
}
