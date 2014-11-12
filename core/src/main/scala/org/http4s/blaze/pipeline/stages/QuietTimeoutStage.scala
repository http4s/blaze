package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.util.Execution._
import org.http4s.blaze.util.TickWheelExecutor

import scala.concurrent.Future
import scala.concurrent.duration.Duration


class QuietTimeoutStage[T](timeout: Duration, exec: TickWheelExecutor = scheduler) extends TimeoutStageBase[T](timeout, exec) {

  ////////////////////////////////////////////////////////////////////////////////

  override def readRequest(size: Int): Future[T] = {
    resetTimeout()
    channelRead(size).map{t =>
      cancelTimeout()
      t
    }(directec)
  }

  override def writeRequest(data: Seq[T]): Future[Unit] = {
    resetTimeout()
    channelWrite(data).map{t =>
      cancelTimeout()
      t
    }(directec)
  }

  override def writeRequest(data: T): Future[Unit] = {
    resetTimeout()
    channelWrite(data).map{t =>
      cancelTimeout()
      t
    }(directec)
  }
}
