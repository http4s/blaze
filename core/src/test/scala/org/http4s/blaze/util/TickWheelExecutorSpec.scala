package org.http4s.blaze.util

import java.util.concurrent.{CountDownLatch, TimeUnit}

import org.specs2.mutable._
import java.util.concurrent.atomic.AtomicInteger

class TickWheelExecutorSpec extends Specification {
  import scala.concurrent.duration._

  "TickWheelExecutor" should {
    val ec = new TickWheelExecutor(tick = 100.millis)

    "Execute a simple task with no delay" in {
      val i = new AtomicInteger(0)
      ec.schedule(new Runnable {
        def run(): Unit = i.set(1)
      }, Duration.Zero)

      i.get() should_== 1
    }

    "Execute a simple task with negative delay" in {
      val i = new AtomicInteger(0)
      ec.schedule(new Runnable {
        def run(): Unit = i.set(1)
      }, -2.seconds)

      i.get() should_== 1
    }

    "Not schedule a simple task with Inf delay" in {
      val i = new AtomicInteger(0)
      ec.schedule(new Runnable {
        def run(): Unit = i.set(1)
      }, Duration.Inf)
      Thread.sleep(100)
      i.get() should_== 0
    }

    "Execute a simple task with a short delay" in {
      val l = new CountDownLatch(1)
      ec.schedule(new Runnable {
        def run(): Unit = l.countDown()
      }, 200.millis)

      l.await(10, TimeUnit.SECONDS) must beTrue
      ok
    }

    "Execute a simple task with a multi clock revolution delay" in {
      val ec = new TickWheelExecutor(3, 2.seconds)
      val latch = new CountDownLatch(1)
      ec.schedule(new Runnable {
        def run(): Unit = latch.countDown()
      }, 7.seconds)

      Thread.sleep(4000) // Shouldn't be done yet
      latch.getCount must_== 1

      latch.await(10, TimeUnit.SECONDS) must beTrue
      ok
    }

    "Execute many delayed tasks" in {
      val ec = new TickWheelExecutor(3, 3.millis)
      val latch = new CountDownLatch(1000)

      (0 until 1000).foreach { j =>
        ec.schedule(new Runnable {
          def run(): Unit = latch.countDown()
        }, j.millis)
      }

      latch.await(10, TimeUnit.SECONDS) must beTrue
      ok
    }

    "Prune many tasks" in {
      val ec = new TickWheelExecutor(3, 10.millis)
      val latch = new CountDownLatch(500)

      val cancels = (0 until 1000).map { j =>
        val c = ec.schedule(new Runnable {
          def run(): Unit = latch.countDown()
        }, (j + 500).millis)
        c
      }
      cancels.zipWithIndex.foreach { case (r, i) => if (i % 2 == 0) r.cancel() }

      latch.await(10, TimeUnit.SECONDS) must beTrue
      ok
    }

    "Gracefully handle exceptions" in {
      val latch = new CountDownLatch(2)
      val ec = new TickWheelExecutor(3, 1.millis) {
        override protected def onNonFatal(t: Throwable): Unit = {
          latch.countDown()
          super.onNonFatal(t)
        }
      }

      ec.schedule(new Runnable {
        def run(): Unit =
          sys.error("Woops!")
      }, 3.millis)

      ec.schedule(new Runnable {
        def run(): Unit =
          sys.error("Woops!")
      }, Duration.Zero)

      latch.await(5, TimeUnit.SECONDS) must beTrue
    }

    "Shutdown" in {
      val ec = new TickWheelExecutor(3, 10.millis)
      ec.shutdown()

      ec.schedule(new Runnable {
        def run(): Unit = sys.error("Woops!")
      }, Duration.Zero) must throwA[RuntimeException]

      ec.schedule(new Runnable {
        def run(): Unit = sys.error("Woops!")
      }, Duration.Inf) must throwA[RuntimeException]
    }
  }
}
