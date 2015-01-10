package org.http4s.blaze.util

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

/** Lightweight actor system HEAVILY inspired by the scalaz actors
  * scalaz actors would have been a good fit except a heavyweight dependency
  * is very undesirable. In addition, the actors defined below support the
  * ask pattern, returning a Future[Any] that will be fulfilled by the result
  * of the function used to build the actor.
  *
  * As an actor begins with a synchronizing operation, any mutable state that
  * it closes over should be updated when it begins processing its mailbox. If it
  * interacts with shared mutable state that will be modified by other threads at
  * the same time, the same memory safety rules apply.
  */

object Actors {
  private[this] val logger = org.log4s.getLogger

  def make[M](f: M => Any, onError: (Throwable, M) => Unit = defaultOnError(_: Throwable,_: M))(implicit ec: ExecutionContext): Actor[M] =
    new Actor(f, onError, ec)

  final class Actor[M]private[Actors](f: M => Any, onError: (Throwable, M) => Unit, ec: ExecutionContext) {
    // Keep the head of the chain
    private val head = new AtomicReference[Node]()
    // keep a reusable runner around, no need to make more garbage
    private val runner = new RecycleableRunnable(null)

    def !(msg: M): Unit = {
      val n = new Node(msg)
      val tail = head.getAndSet(n)
      if (tail eq null) {   // Execute
        runner.start = n
        ec.execute(runner)
      }
      else tail.lazySet(n)
    }


    private class Node(val m: M) extends AtomicReference[Node]()
    private class RecycleableRunnable(var start: Node) extends Runnable {

      override def run(): Unit = go(start, 512)

      @tailrec
      private def go(next: Node, limit: Int): Unit = {
        val m = next.m
        try f(next.m)
        catch { case t: Throwable =>
          try onError(t, m)   // who knows what people will be feeding in here...
          catch { case t: Throwable => logger.error(t)(s"Error during execution of `onError` in Actor. Msg: $m") }
        }
        // continue processing the mailbox
        val n2 = next.get()
        if (n2 eq null) {
          if (!head.compareAndSet(next, null)) {
          // someone just added a Node, spin until it resolves
            val n2 = spin(next)
            if (limit > 0) go(n2, limit - 1)
            else reSchedule(n2)
          } // else: we are finished
        } else { // more to go
          if (limit > 0) go(n2, limit - 1)
          else reSchedule(n2)
        }
      }

      private def reSchedule(node: Node) = {
        start = node
        ec.execute(this)
      }

      @tailrec
      private def spin(prev: Node): Node = {
        val n2 = prev.get()
        if (n2 eq null) spin(prev)
        else n2
      }
    }
  }

  private def defaultOnError(t: Throwable, msg: Any) = logger.error(t)(s"Error executing actor with message $msg")
}
