package org.http4s.blaze.pipeline
package stages

import java.util.concurrent.TimeoutException
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import org.http4s.blaze.pipeline.Command.InboundCommand
import org.http4s.blaze.util.{Cancellable, TickWheelExecutor}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.Duration

abstract class TimeoutStageBase[T](timeout: Duration, exec: TickWheelExecutor)
    extends MidStage[T, T] { stage =>

  import TimeoutStageBase.closedTag

  // Constructor
  require(timeout.isFinite() && timeout.toMillis != 0, s"Invalid timeout: $timeout")

  override def name: String = s"${this.getClass.getName} Stage: $timeout"

  /////////// Private impl bits //////////////////////////////////////////

  private val lastTimeout =
    new AtomicReference[Cancellable](Cancellable.NoopCancel)

  private val killswitch = new Runnable {
    override def run(): Unit = {
      sendInboundCommand(TimeoutStageBase.TimedOut(timeout))
    }
  }

  @tailrec
  final def setAndCancel(next: Cancellable): Unit = {
    val prev = lastTimeout.getAndSet(next)
    if (prev == closedTag && next != closedTag) {
      // woops... we submitted a new cancellation when we were closed!
      next.cancel()
      setAndCancel(closedTag)
    } else prev.cancel()
  }

  /////////// Pass through implementations ////////////////////////////////

  override def readRequest(size: Int): Future[T] = channelRead(size)

  override def writeRequest(data: T): Future[Unit] = channelWrite(data)

  override def writeRequest(data: Seq[T]): Future[Unit] = channelWrite(data)

  /////////// Protected impl bits //////////////////////////////////////////

  override protected def stageShutdown(): Unit = {
    setAndCancel(closedTag)
    super.stageShutdown()
  }

  final protected def resetTimeout(): Unit =
    setAndCancel(exec.schedule(killswitch, timeout))

  final protected def cancelTimeout(): Unit =
    setAndCancel(Cancellable.NoopCancel)

  final protected def startTimeout(): Unit = resetTimeout()
}

object TimeoutStageBase {
  // Represents the situation where the pipeline has been closed.
  private[TimeoutStageBase] val closedTag = new Cancellable {
    override def cancel(): Unit = ()
  }

  final case class TimedOut(timeout: Duration) extends InboundCommand
}
