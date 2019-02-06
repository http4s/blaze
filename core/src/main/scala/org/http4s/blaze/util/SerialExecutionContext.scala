package org.http4s.blaze.util

import scala.concurrent.ExecutionContext

/** Serialize execution of work, ensuring that no passed work is executed in parallel.
  *
  * Tasks are executed sequentially, in the order they are offered. Each task has a
  * happens-before relationship with subsequent tasks, meaning mutations performed
  * in a task are observed by all sequent tasks.
  *
  * @param parent `ExecutionContext` with which to perform the work, which may consist
  *              of many tasks queued in the `SerialExecutionContext`.
  */
class SerialExecutionContext(
    parent: ExecutionContext
) extends ExecutionContext {

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
