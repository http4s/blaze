package org.http4s.blaze.util

import org.http4s.blaze.pipeline.Command
import org.specs2.mutable.Specification

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, Awaitable}
import scala.concurrent.duration._

class ReadPoolSpec extends Specification {
  private def await[T](t: Awaitable[T]): T = Await.result(t, 1.second)

  private class TrackingReadPool extends ReadPool[Int] {
    private[this] val obs = new ListBuffer[Int]

    def observed: List[Int] = obs.result()

    override def messageConsumed(t: Int): Unit = {
      obs += t
      ()
    }
  }

  "ReadPool" should {
    "enqueue an offer" in {
      val p = new TrackingReadPool
      p.offer(1) must beTrue
      p.observed must beEmpty

      await(p.read()) must_== 1
      p.observed must_== List(1)
    }

    "enqueue multiple messages" in {
      val p = new TrackingReadPool
      (0 until 10).foreach { i =>
        p.offer(i) must beTrue
        p.observed must beEmpty
      }

      forall(0 until 10) { i =>
        await(p.read()) must_== i
        p.observed must_== (0 to i).toList
      }
    }

    "enqueue a single read" in {
      val p = new TrackingReadPool
      val f = p.read()

      f.value must beNone
      p.offer(1) must beTrue
      await(f) must_== 1
    }

    "fail to enqueue two reads" in {
      val p = new TrackingReadPool
      p.read()
      await(p.read()) must throwAn[IllegalStateException]
    }

    "close fails pending reads" in {
      val p = new TrackingReadPool
      val f = p.read()
      p.close()
      await(f) must throwA[Command.EOF.type]
      // subsequent reads fail too
      await(p.read()) must throwA[Command.EOF.type]

      // Offers must return false after closed
      p.offer(1) must beFalse
    }
  }
}
