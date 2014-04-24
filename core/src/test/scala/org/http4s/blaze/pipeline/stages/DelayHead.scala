package org.http4s.blaze.pipeline.stages

import scala.concurrent.duration.Duration
import org.http4s.blaze.pipeline.HeadStage
import scala.concurrent.{Promise, Future}
import org.http4s.blaze.util.Execution
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
    }, delay)
    p.future
  }

  override def writeRequest(data: I): Future[Unit] = {
    val p = Promise[Unit]
    Execution.scheduler.schedule(new Runnable {
      def run() { p.trySuccess() }
    }, delay)
    p.future
  }
}
