package org.http4s.blaze.util

import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.concurrent.Eventually

/**
 * @author Bryce Anderson
 *         Created on 2/3/14
 */
class TickWheelExecutorSpec extends WordSpec with Matchers with Eventually {

  "TickWheelExecutor" should {

    val ec = new TickWheelExecutor(resolution = 100.millis)

    "Execute a simple task with no delay" in {
      val i = new AtomicInteger(0)
      ec.schedule(new Runnable {
        def run() { i.set(1) }
      }, Duration.Zero)

      i.get() should equal(1)

    }

    "Execute a simple task with a short delay" in {
      val i = new AtomicInteger(0)
      ec.schedule(new Runnable {
        def run() { i.set(1) }
      }, 200.millis)
      Thread.sleep(400)
      i.get() should equal(1)
    }

    "Execute a simple task with a multi clock revolution delay" in {
      val ec = new TickWheelExecutor(3, 20.millis)
      val i = new AtomicInteger(0)
      ec.schedule(new Runnable {
        def run() { i.set(1) }
      }, 119.millis)

      Thread.sleep(85)
      i.get should equal(0)

      Thread.sleep(100)
      i.get should equal(1)

    }

    "Execute many delayed tasks" in {
      val ec = new TickWheelExecutor(3, 3.millis)
      val i = new AtomicInteger(0)

      0 until 1000 foreach { _ =>
        ec.schedule(new Runnable {
          def run() { i.incrementAndGet() }
        }, 13.millis)
        Thread.sleep(1)
      }

      Thread.sleep(1020)
      i.get() should equal(1000)

    }

    "Prune many tasks" in {
      val ec = new TickWheelExecutor(3, 10.millis)
      val i = new AtomicInteger(0)

      val cancels = 0 until 1000 map { j =>
        val c = ec.schedule(new Runnable {
          def run() { i.incrementAndGet() }
        }, ((j+20)*10).millis)
        c
      }
      cancels.foreach(_.cancel())

      Thread.sleep(700)
      i.get() should equal(0)

    }

    "Gracefully handle exceptions" in {
      @volatile var failed = 0
      val ec = new TickWheelExecutor(3, 1.millis) {
        override protected def onNonFatal(t: Throwable): Unit = {
          failed += 1
          super.onNonFatal(t)
        }
      }

      ec.schedule(new Runnable{
        def run() {
          sys.error("Woops!")
        }
      }, 3.millis)

      ec.schedule(new Runnable{
        def run() {
          sys.error("Woops!")
        }
      }, Duration.Zero)

      Thread.sleep(10)
      failed should equal(2)
    }

    "Shutdown" in {
      val ec = new TickWheelExecutor(3, 10.millis)
      ec.shutdown()

      a [RuntimeException] should be thrownBy ec.schedule(new Runnable{
                                              def run() {
                                                sys.error("Woops!")
                                              }
                                            }, Duration.Zero)
    }
  }

}
