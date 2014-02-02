package blaze.pipeline.stages

import scala.concurrent.duration.Duration
import blaze.pipeline.HeadStage
import scala.concurrent.{Promise, Future}
import blaze.util.Execution
import java.util.concurrent.TimeUnit

/**
 * @author Bryce Anderson
 *         Created on 2/2/14
 */
abstract class DelayHead[I](delay: Duration) extends HeadStage[I] {

  def next(): I

  def name: String = "DelayHead"

  override def readRequest(size: Int): Future[I] = {
    val p = Promise[I]
    Execution.scheduler.schedule(new Runnable {
      def run() { p.trySuccess(next()) }
    }, delay.toNanos, TimeUnit.NANOSECONDS)
    p.future
  }

  override def writeRequest(data: I): Future[Any] = {
    val p = Promise[Any]
    Execution.scheduler.schedule(new Runnable {
      def run() { p.trySuccess("done") }
    }, delay.toNanos, TimeUnit.NANOSECONDS)
    p.future
  }
}
