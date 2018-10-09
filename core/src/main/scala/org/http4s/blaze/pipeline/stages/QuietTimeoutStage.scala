package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.util.Execution._
import org.http4s.blaze.util.{Cancelable, TickWheelExecutor}
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Success, Try}

/** Shut down the pipeline after a period of inactivity */
trait QuietTimeoutStage[A] extends TimeoutStage[A, A] {

  def name = "QuietTimeoutStage"

  ////////////////////////////////////////////////////////////////////////////////

  final override protected def stageStartup(): Unit = {
    super.stageStartup()
    startTimeout()
  }

  final override def readRequest(size: Int): Future[A] = {
    val f = channelRead(size)
    f.onComplete { _ =>
      resetTimeout()
    }(directec)
    f
  }

  final override def writeRequest(data: Seq[A]): Future[Unit] = {
    val f = channelWrite(data)
    f.onComplete { _ =>
      resetTimeout()
    }(directec)
    f
  }

  final override def writeRequest(data: A): Future[Unit] = {
    val f = channelWrite(data)
    f.onComplete { _ =>
      resetTimeout()
    }(directec)
    f
  }
}

object QuietTimeoutStage {
  private val SuccessUnit = Success(())

  def apply[A](timeout: FiniteDuration, tickWheelExecutor: TickWheelExecutor): QuietTimeoutStage[A] =
    new QuietTimeoutStage[A] {
      protected def scheduleTimeout(cb: Try[Unit] => Unit): Cancelable = {
        val r = new Runnable {
          def run() = cb(SuccessUnit)
        }
        tickWheelExecutor.schedule(r, timeout)
      }
    }
}
