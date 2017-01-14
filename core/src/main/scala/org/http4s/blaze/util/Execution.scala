package org.http4s.blaze.util

import scala.concurrent.{ExecutionContext, Future, Promise}
import java.util

import org.log4s.getLogger

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.util.Try

/** Special `ExecutionContext` instances.
  */
object Execution {
  private[this] val logger = getLogger

  // Race a future vs a default value. The timer used is the default blaze scheduler
  private[blaze] def withTimeout[T](d: Duration, fallback: Try[T])(f: Future[T]): Future[T] = {
    if (!d.isFinite) f
    else if (f.isCompleted) f
    else {
      val p = Promise[T]
      val r = new Runnable {
        def run(): Unit = {
          if (p.tryComplete(fallback)) {
            logger.debug(s"Future $f timed out after $d, yielding reesult $fallback")
          }
        }
      }
      // Race the future vs a timeout
      Execution.scheduler.schedule(r, d)
      f.onComplete(p.tryComplete(_))(Execution.directec)
      p.future
    }
  }

  /** A trampolining `ExecutionContext`
    *
    * This `ExecutionContext` is run thread locally to avoid context switches.
    * Because this is a thread local executor, if there is a dependence between
    * the submitted `Runnable`s and the thread becomes blocked, there will be
    * a deadlock.
    */
  val trampoline: ExecutionContext = new ExecutionContext {

    private val local = new ThreadLocal[ThreadLocalTrampoline]

    def execute(runnable: Runnable): Unit = {
      var queue = local.get()
      if (queue == null) {
        queue = new ThreadLocalTrampoline
        local.set(queue)
      }

      queue.execute(runnable)
    }

    def reportFailure(t: Throwable): Unit = trampolineFailure(t)
  }

  /** Execute `Runnable`s directly on the current thread, using a stack frame.
    *
    * This is not safe to use for recursive function calls as you will ultimately
    * encounter a stack overflow. For those situations, use `trampoline`.
    */
  val directec: ExecutionContext = new ExecutionContext {

    def execute(runnable: Runnable): Unit = runnable.run()

    def reportFailure(t: Throwable): Unit = {
      logger.error(t)("Error encountered in Direct EC")
      throw t
    }
  }

  // Should only be used for scheduling timeouts for channel writes
  private[blaze] lazy val scheduler: TickWheelExecutor = new TickWheelExecutor()

  // Only safe to use from a single thread
  private final class ThreadLocalTrampoline extends ExecutionContext {
    private var running = false
    private var r0, r1, r2: Runnable = null
    private var rest: util.ArrayDeque[Runnable] = null

    override def execute(runnable: Runnable): Unit = {
      if (r0 == null) r0 = runnable
      else if (r1 == null) r1 = runnable
      else if (r2 == null) r2 = runnable
      else {
        if (rest == null) rest = new util.ArrayDeque[Runnable]()
        rest.add(runnable)
      }

      if (!running) {
        running = true
        run()
      }
    }

    override def reportFailure(cause: Throwable): Unit = trampolineFailure(cause)

    @tailrec
    private def run(): Unit = {
      val r = next()
      if (r == null) {
        rest = null     // don't want a memory leak from potentially large array buffers
        running = false
      } else {
        try r.run()
        catch { case e: Throwable => reportFailure(e) }
        run()
      }
    }

    private def next(): Runnable = {
      val r = r0
      r0 = r1
      r1 = r2
      r2 = if (rest != null) rest.pollFirst() else null
      r
    }
  }

  private def trampolineFailure(cause: Throwable) = logger.error(cause)("Trampoline EC Exception caught")
}
