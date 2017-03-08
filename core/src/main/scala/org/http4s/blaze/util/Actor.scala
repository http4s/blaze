package org.http4s.blaze.util

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext

/** Lightweight actor system HEAVILY inspired by the scalaz actors.
  * scalaz actors would have been a good fit except a heavyweight dependency
  * is very undesirable for this library.
  */

private[blaze] abstract class Actor[M](ec: ExecutionContext) {

  // Keep the head of the chain
  private[this] val head = new AtomicReference[Node]()
  // keep a reusable runner around, no need to make more garbage on every actor startup
  private[this] val runner = new RecycleableRunnable(null)

  /** The canonical message handling function */
  protected def act(message: M): Unit

  /** Handler of errors throw during the `act` function */
  protected def onError(t: Throwable, msg: M): Unit =
    Actor.logger.error(t)(s"Unhandled exception throw when processing message '$msg'.")

  /** Submit a message to the `Actor`.
    *
    * If the `Actor` is not running, it will be scheduled in the provided `ExecutionContext`.
    */
  final def !(msg: M): Unit = {
    val n = new Node(msg)
    val tail = head.getAndSet(n)
    if (tail eq null) {   // Execute
      runner.start = n
      ec.execute(runner)
    }
    else tail.lazySet(n)
  }

  private[this] class Node(val m: M) extends AtomicReference[Node]
  private[this] class RecycleableRunnable(@volatile var start: Node) extends Runnable {

    override def run(): Unit = {
      // We use a while loop and mutation to avoid keeping a
      // link to the head of the Node list, although its unlikely
      // to be a practical problem with low cycle quotas.
      var next = start
      start = null

      var i = 0
      while (i < 256) {
        i += 1
        val m = next.m
        try act(m)
        catch { case t:Throwable =>
          try onError(t, m)
          catch { case t: Throwable =>
            Actor.logger.error(t)(s"Error during execution of `onError` in Actor. Msg: $m")
          }
        }

        val maybeNext = next.get()
        if (maybeNext == null) {
          // We have reached the end of the list. Check for a race to add a new element.
          if (head.compareAndSet(next, null)) return
          else {
            // someone just added a Node, so spin until the link resolves
            next = spin(next)
          }
        } else {
          // continue
          next = maybeNext
        }
      }

      // reached our quota. Reschedule.
      reSchedule(next)
    }

    private[this] def reSchedule(node: Node) = {
      start = node
      ec.execute(this)
    }

    @tailrec
    private[this] def spin(prev: Node): Node = {
      val n2 = prev.get()
      if (n2 eq null) spin(prev)
      else n2
    }
  }
}

private object Actor {
  private val logger = org.log4s.getLogger
}
