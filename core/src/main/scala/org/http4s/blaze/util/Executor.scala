package org.http4s.blaze.util

import scala.concurrent.ExecutionContext
import java.util
import scala.util.control.NonFatal
import org.log4s.getLogger


object Execution {
  private[this] val logger = getLogger

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
        } catch {
          case t: Throwable =>
            reportFailure(t)
            if (!NonFatal(t)) throw t // rethrow a fatal exception
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

    def reportFailure(t: Throwable): Unit = logger.error(t)("Trampoline EC Exception caught")
  }

  val directec: ExecutionContext = new ExecutionContext {

    def execute(runnable: Runnable): Unit = runnable.run()

    def reportFailure(t: Throwable): Unit = {
      logger.error(t)("Error encountered in Direct EC")
      throw t
    }
  }

  // Should only be used for scheduling timeouts for channel writes
  private[blaze] lazy val scheduler: TickWheelExecutor = new TickWheelExecutor()
}
