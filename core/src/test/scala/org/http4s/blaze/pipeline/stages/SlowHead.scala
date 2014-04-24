package org.http4s.blaze.pipeline
package stages

import scala.concurrent.{Promise, Future}
import scala.util.Random
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext.Implicits.global

/**
 * @author Bryce Anderson
 *         Created on 1/7/14
 */
trait SlowHead[O] extends HeadStage[O] {

  def get: O

  def write(data: O): Unit

  private val readGuard = new AtomicBoolean(false)
  private val writeGuard = new AtomicBoolean(false)

  def readRequest(size: Int): Future[O] = {
    if (readGuard.getAndSet(true)) Future(sys.error("Read guard breached!"))
    else {
      val p = Promise[O]
      new Thread {
        override def run() {
          val delay = Random.nextFloat()*10
          Thread.sleep(delay.toInt)
          if (readGuard.compareAndSet(true, false)) p.success(get)
          else p.completeWith(Future(sys.error("Read guard breached!")))
        }
      }.start()

      p.future
    }
  }

  def writeRequest(data: O): Future[Unit] = {
    if (writeGuard.getAndSet(true)) Future(sys.error("Write guard breached!"))
    else {
      write(data)
      val p = Promise[Unit]
      new Thread {
        override def run() {
          val delay = Random.nextFloat()*20
          Thread.sleep(delay.toInt)
          if (writeGuard.compareAndSet(true, false)) p.success(get)
          else p.completeWith(Future(sys.error("Write guard breached!")))
        }
      }.start()

      p.future
    }

  }
}
