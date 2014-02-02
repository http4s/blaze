package blaze.util

import scala.concurrent.ExecutionContext
import com.typesafe.scalalogging.slf4j.Logging
import java.util
import java.util.concurrent.{Executors, ScheduledExecutorService}

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 *
 *         Essentially directly copied from the Play2 framework
 */
object Execution extends Logging {

  val trampoline: ExecutionContext = new ExecutionContext {

    private val local = new ThreadLocal[util.Deque[Runnable]]

    def execute(runnable: Runnable): Unit = {
      var queue = local.get()
      if (queue == null) {
        // Since there is no local queue, we need to install one and
        // start our trampolining loop.
        try {
          queue = new util.ArrayDeque(4)
          queue.addLast(runnable)
          local.set(queue)
          while (!queue.isEmpty) {
            val runnable = queue.removeFirst()
            runnable.run()
          }
        } finally {
          // We've emptied the queue, so tidy up.
          local.set(null)
        }
      } else {
        // There's already a local queue that is being executed.
        // Just stick our runnable on the end of that queue.
        queue.addLast(runnable)
      }
    }

    def reportFailure(t: Throwable): Unit = t.printStackTrace()
  }

  val directec: ExecutionContext = new ExecutionContext {

    def execute(runnable: Runnable): Unit = runnable.run()

    def reportFailure(t: Throwable): Unit = {
      logger.error("Error encountered in Direct EC", t)
      throw t
    }
  }

  // Should only be used for scheduling timeouts for channel writes
  lazy val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
}
