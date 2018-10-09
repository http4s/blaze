package org.http4s.blaze.pipeline
package stages

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import org.http4s.blaze.pipeline.Command.InboundCommand
import org.http4s.blaze.util.{Cancelable, TickWheelExecutor}
import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

trait TimeoutStage[I, O] extends MidStage[I, O] { stage =>
  import TimeoutStage.closedTag

  private[this] val timedOutPromise = Promise[Unit]
  val timedOut: Future[Unit] = timedOutPromise.future

  /////////// Private impl bits //////////////////////////////////////////

  private val lastTimeout =
    new AtomicReference[Cancelable](Cancelable.NoopCancel)

  @tailrec
  final def setAndCancel(next: Cancelable): Unit = {
    val prev = lastTimeout.getAndSet(next)
    if (prev == closedTag && next != closedTag) {
      // woops... we submitted a new cancellation when we were closed!
      next.cancel()
      setAndCancel(closedTag)
    } else prev.cancel()
  }

  /////////// Protected impl bits //////////////////////////////////////////

  override protected def stageShutdown(): Unit = {
    setAndCancel(closedTag)
    super.stageShutdown()
  }

  final protected def resetTimeout(): Unit =
    setAndCancel(scheduleTimeout(timedOutPromise.tryComplete))

  protected def scheduleTimeout(cb: Try[Unit] => Unit): Cancelable

  final protected def cancelTimeout(): Unit =
    setAndCancel(Cancelable.NoopCancel)

  final protected def startTimeout(): Unit = resetTimeout()
}

object TimeoutStage {
  // Represents the situation where the pipeline has been closed.
  private val closedTag = new Cancelable {
    override def cancel(): Unit = ()
  }
}
