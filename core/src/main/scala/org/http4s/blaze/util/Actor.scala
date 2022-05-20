/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.util

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import org.http4s.blaze.util.Actor.DefaultMaxIterations

/** Lightweight actor system HEAVILY inspired by the scalaz actors. scalaz actors would have been a
  * good fit except a heavyweight dependency is very undesirable for this library.
  */
private[blaze] abstract class Actor[M](
    ec: ExecutionContext,
    maxTasksBeforeReschedule: Int = DefaultMaxIterations
) {
  require(maxTasksBeforeReschedule > 0)

  // Keep the tail of the chain
  private[this] val tailNode = new AtomicReference[Node]()
  // keep a reusable runner around, no need to make more garbage on every actor startup
  private[this] val runner = new RecycleableRunnable(null)

  /** The canonical message handling function */
  protected def act(message: M): Unit

  /** Handler of errors throw during the `act` function
    *
    * This method must not throw.
    */
  protected def onError(t: Throwable, msg: M): Unit =
    Actor.logger.error(t)(s"Unhandled exception throw when processing message '$msg'.")

  /** Submit a message to the `Actor`.
    *
    * If the `Actor` is not running, it will be scheduled in the provided `ExecutionContext`.
    */
  final def !(msg: M): Unit = {
    val n = new Node(msg)
    val tail = tailNode.getAndSet(n)
    if (tail eq null) { // Execute
      runner.start = n
      ec.execute(runner)
    } else tail.lazySet(n)
  }

  private[this] class Node(val m: M) extends AtomicReference[Node]
  private[this] class RecycleableRunnable(@volatile var start: Node) extends Runnable {
    override def run(): Unit = {
      @tailrec
      def go(i: Int, next: Node): Unit =
        if (i >= maxTasksBeforeReschedule) reSchedule(next)
        else {
          val m = next.m
          try act(m)
          catch { case NonFatal(t) => onError(t, m) }

          val maybeNext = next.get
          if (maybeNext != null) go(i + 1, maybeNext)
          else
          // We have reached the end of the list. Check for a race to add a new element.
          if (!tailNode.compareAndSet(next, null))
            // someone just added a Node, so spin until the link resolves
            go(i + 1, spin(next))
        }
      val next = start
      start = null
      go(0, next)
    }

    private[this] def reSchedule(node: Node) = {
      start = node
      ec.execute(this)
    }

    @tailrec
    private[this] def spin(prev: Node): Node = {
      val next = prev.get
      if (next eq null) spin(prev)
      else next
    }
  }
}

private object Actor {
  private val logger = org.log4s.getLogger

  // We don't want to bogart the thread indefinitely so we reschedule
  // ourselves after 256 iterations to allow other work to interleave.
  val DefaultMaxIterations = 256
}
