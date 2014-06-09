package org.http4s.blaze.pipeline.stages

import scala.concurrent.duration.Duration
import org.http4s.blaze.pipeline.{Command, MidStage}
import scala.concurrent.Future
import org.http4s.blaze.util.{Cancellable, TickWheelExecutor}

import org.http4s.blaze.util.Execution.scheduler
import java.util.concurrent.atomic.AtomicReference

/**
 * Created by Bryce Anderson on 6/8/14.
 */
class TimeoutStage[T](timeout: Duration, exec: TickWheelExecutor = scheduler) extends MidStage[T, T] { stage =>

  // Constructor
  if (!timeout.isFinite()) sys.error(s"Cannot use a TimeoutStage with infinite timeout: $timeout")

  override def name: String = s"Timeout Stage: $timeout"

  ////////////////////////////////////////////////////////////////////////////////

  override def readRequest(size: Int): Future[T] = {
    resetTimeout()
    channelRead(size)
  }

  override def writeRequest(data: Seq[T]): Future[Unit] = {
    resetTimeout()
    channelWrite(data)
  }

  override def writeRequest(data: T): Future[Unit] = {
    resetTimeout()
    channelWrite(data)
  }

  /////////// Private impl bits //////////////////////////////////////////

  private val lastTimeout = new AtomicReference[Cancellable](null)

  private val killswitch = new Runnable {
    override def run(): Unit = {
      stage.sendOutboundCommand(Command.Disconnect)
    }
  }

  private def resetTimeout() = {
    val next = scheduler.schedule(killswitch, timeout)
    val prev = lastTimeout.getAndSet(next)

    if (prev != null) prev.cancel()
  }
}
