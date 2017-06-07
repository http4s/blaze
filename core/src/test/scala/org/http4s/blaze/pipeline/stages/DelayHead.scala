package org.http4s.blaze.pipeline.stages

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.Duration
import org.http4s.blaze.pipeline.{Command, HeadStage}
import scala.concurrent.{Promise, Future}
import org.http4s.blaze.util.TimingTools
import scala.collection.mutable

abstract class DelayHead[I](delay: Duration) extends HeadStage[I] {

  def next(): I

  def name: String = "DelayHead"

  private val awaitingPromises = new mutable.HashSet[Promise[_]]()

  private def rememberPromise(p: Promise[_]): Unit = {
    awaitingPromises.synchronized {
      awaitingPromises += p
    }
    ()
  }

  private def unqueue(p: Promise[_]): Unit = {
    awaitingPromises.synchronized {
      awaitingPromises.remove(p)
    }
    ()
  }

  override def readRequest(size: Int): Future[I] = {
    val p = Promise[I]

    rememberPromise(p)

    TimingTools.highres.schedule(new Runnable {
      def run(): Unit = {
        p.trySuccess(next())
        unqueue(p)
      }
    }, delay.toNanos, TimeUnit.NANOSECONDS)
    p.future
  }



  override def writeRequest(data: I): Future[Unit] = {
    val p = Promise[Unit]
    TimingTools.highres.schedule(new Runnable {
      def run(): Unit = {
        p.trySuccess(())
        unqueue(p)
      }
    }, delay.toNanos, TimeUnit.NANOSECONDS)

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
