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
        def run(): Unit = { i.set(1) }
      }, Duration.Zero)

      i.get() should_== 1
    }

    "Execute a simple task with negative delay" in {
      val i = new AtomicInteger(0)
      ec.schedule(new Runnable {
        def run(): Unit = { i.set(1) }
      }, -2.seconds)

      i.get() should_== 1
    }

    "Not schedule a simple task with Inf delay" in {
      val i = new AtomicInteger(0)
      ec.schedule(new Runnable {
        def run(): Unit = { i.set(1) }
      }, Duration.Inf)
      Thread.sleep(100)
      i.get() should_== 0
    }

    "Execute a simple task with a short delay" in {
      val i = new AtomicInteger(0)
      ec.schedule(new Runnable {
        def run(): Unit = { i.set(1) }
      }, 200.millis)
      TimingTools.spin(5.seconds)(i.get == 1)
      i.get() should_== 1
    }

    "Execute a simple task with a multi clock revolution delay" in {
      val ec = new TickWheelExecutor(3, 2.seconds)
      val i = new AtomicInteger(0)
      ec.schedule(new Runnable {
        def run(): Unit = { i.set(1) }
      }, 7.seconds)

      TimingTools.spin(5.seconds)(false)
      i.get should_== 0

      TimingTools.spin(10.seconds)(i.get == 1)
      i.get should_== 1

    }

    "Execute many delayed tasks" in {
      val ec = new TickWheelExecutor(3, 3.millis)
      val i = new AtomicInteger(0)

      0 until 1000 foreach { j =>
        ec.schedule(new Runnable {
          def run(): Unit = { i.incrementAndGet(); () }
        }, j.millis)
      }

      TimingTools.spin(10.seconds)(i.get == 1000)
      i.get() should_== 1000

    }

    "Prune many tasks" in {
      val ec = new TickWheelExecutor(3, 10.millis)
      val i = new AtomicInteger(0)

      val cancels = 0 until 1000 map { j =>
        val c = ec.schedule(new Runnable {
          def run(): Unit = { i.incrementAndGet(); () }
        }, (j+500).millis)
        c
      }
      cancels.zipWithIndex.foreach{ case (r, i) => if (i % 2 == 0) r.cancel() }

      TimingTools.spin(10.seconds)(i.get == 500)
      i.get() should_== 500
    }

    "Gracefully handle exceptions" in {
      val latch = new CountDownLatch(2)
      val ec = new TickWheelExecutor(3, 1.millis) {
        override protected def onNonFatal(t: Throwable): Unit = {
          latch.countDown()
          super.onNonFatal(t)
        }
      }

      ec.schedule(new Runnable{
        def run(): Unit = {
          sys.error("Woops!")
        }
      }, 3.millis)

      ec.schedule(new Runnable{
        def run(): Unit = {
          sys.error("Woops!")
        }
      }, Duration.Zero)

      latch.await(5, TimeUnit.SECONDS) must beTrue
    }

    "Shutdown" in {
      val ec = new TickWheelExecutor(3, 10.millis)
      ec.shutdown()

      ec.schedule(new Runnable{
         def run(): Unit = { sys.error("Woops!")}
      }, Duration.Zero) must throwA[RuntimeException]

      ec.schedule(new Runnable{
        def run(): Unit = { sys.error("Woops!")}
      }, Duration.Inf) must throwA[RuntimeException]
    }
  }

}
