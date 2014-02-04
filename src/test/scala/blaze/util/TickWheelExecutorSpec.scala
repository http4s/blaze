package blaze.util

import org.scalatest.{Matchers, WordSpec}
import scala.concurrent.{Promise, Future}

import scala.concurrent.duration._
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author Bryce Anderson
 *         Created on 2/3/14
 */
class TickWheelExecutorSpec extends WordSpec with Matchers {

  def getPair(): (Future[Any], Runnable) = {
    val p = Promise[Any]

    val r = new Runnable {
      def run() { p.success() }
    }

    (p.future, r)
  }

  "TickWheelExecutor" should {

    val ec = new TickWheelExecutor()

    "Execute a simple task with no delay" in {
      @volatile var i = 0
      ec.schedule(new Runnable {
        def run() { i = 1 }
      }, Duration.Zero)

      i should equal(1)

    }

    "Execute a simple task with a short delay" in {
      @volatile var i = 0
      ec.schedule(new Runnable {
        def run() { i = 1 }
      }, 100.millis)
      Thread.sleep(200)
      i should equal(1)
    }

    "Execute a simple task with a multi clock revolution delay" in {
      val ec = new TickWheelExecutor(3, 20.millis)
      @volatile var i = 0
      ec.schedule(new Runnable {
        def run() { i = 1 }
      }, 119.millis)

      Thread.sleep(85)
      i should equal(0)

      Thread.sleep(60)
      i should equal(1)

    }

    "Execute many delayed tasks" in {
      val ec = new TickWheelExecutor(3, 3.millis)
      val i = new AtomicInteger(0)

      0 until 1000 foreach { _ =>
        ec.schedule(new Runnable {
          def run() { i.incrementAndGet() }
        }, 3.millis)
        Thread.sleep(1)
      }

      Thread.sleep(1010)
      i.get() should equal(1000)

    }
  }

}
