package org.http4s.blaze.pipeline.stages

import scala.concurrent.duration.Duration
import org.http4s.blaze.pipeline.{Command, HeadStage}
import scala.concurrent.{Promise, Future}
import org.http4s.blaze.util.Execution
import java.util.concurrent.TimeUnit
import scala.collection.mutable
import java.nio.channels.ClosedChannelException

abstract class DelayHead[I](delay: Duration) extends HeadStage[I] {

  def next(): I

  def name: String = "DelayHead"

  private val awaitingPromises = new mutable.HashSet[Promise[_]]()

  private def rememberPromise(p: Promise[_]): Unit = awaitingPromises.synchronized {
    awaitingPromises += p
  }

  private def unqueue(p: Promise[_]): Unit = awaitingPromises.synchronized {
    awaitingPromises.remove(p)
  }

  override def readRequest(size: Int): Future[I] = {
    val p = Promise[I]

    rememberPromise(p)

    Execution.scheduler.schedule(new Runnable {
      def run() {
        p.trySuccess(next())
        unqueue(p)
      }
    }, delay)
    p.future
  }



  override def writeRequest(data: I): Future[Unit] = {
    val p = Promise[Unit]
    Execution.scheduler.schedule(new Runnable {
      def run() {
        p.trySuccess(())
        unqueue(p)
      }
    }, delay)

    rememberPromise(p)
    p.future
  }

  override protected def stageShutdown(): Unit = {
    awaitingPromises.synchronized {
      awaitingPromises.foreach { p =>
        p.tryFailure(Command.EOF)
      }
    }
    super.stageShutdown()
  }
}
