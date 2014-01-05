package blaze.util

import scala.concurrent.ExecutionContext

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */
object DirectExecutor extends ExecutionContext {
  def execute(runnable: Runnable): Unit = runnable.run()
  def reportFailure(t: Throwable): Unit = throw t

  implicit def direct: ExecutionContext = this
}
