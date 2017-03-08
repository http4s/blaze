package org.http4s.blaze.util

import scala.concurrent.ExecutionContext

/** Serialize execution of work, ensuring that no passed work is executed in parallel.
  *
  * @param parent `ExecutionContext` with which to perform the work. This may execute
  *               work however it sees fit.
  */
private[blaze] class SerialExecutionContext(parent: ExecutionContext)
  extends ExecutionContext {

  private[this] val actor = new Actor[Runnable](parent) {
    override protected def act(work: Runnable): Unit = work.run()
    override protected def onError(t: Throwable, msg: Runnable): Unit =
      reportFailure(t)
  }

  override def execute(runnable: Runnable): Unit =
    actor ! runnable

  override def reportFailure(cause: Throwable): Unit =
    parent.reportFailure(cause)
}
