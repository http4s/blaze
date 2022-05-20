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

import org.log4s.getLogger

import scala.annotation.tailrec
import scala.util.control.{ControlThrowable, NonFatal}

/** Multi-producer single-consumer queue implementation */
private[blaze] final class TaskQueue {
  import TaskQueue._

  // A reference to the head of the queue.
  private[this] val queueHead = new AtomicReference[Node](null)

  // The tail of the queue. Elements added to the queue take this
  // position and 'link' the previous tail to themselves. If no
  // previous tail existed, it is placed in the `queueHead` pointer.
  private[this] val queueTail = new AtomicReference[Node](null)

  /** Whether the queue has been closed */
  def isClosed: Boolean = queueTail.get eq ClosedNode

  /** Whether the queue has tasks pending execution */
  def needsExecution: Boolean = queueHead.get ne null

  /** Close the MPSC queue, executing all pending tasks */
  def close(): Unit = {
    val tail = queueTail.getAndSet(ClosedNode)
    if (tail ne null) {
      // The queue wasn't empty, so we link the
      // tail and execute the remaining tasks
      tail.lazySet(ClosedNode)
      executeTasks()
    }
  }

  /** Attempt to enqueue the task for execution
    *
    * This method is safe to call from multiple threads
    */
  def enqueueTask(runnable: Runnable): Result = {
    val node = new Node(runnable)

    @tailrec
    def loop(): Result = {
      val tail = queueTail.get
      if (tail == ClosedNode) Closed // closed
      else if (!queueTail.compareAndSet(tail, node)) loop() // lost the race
      else if (tail != null) {
        // link the head -> node
        tail.lazySet(node)
        Enqueued
      } else {
        // this was the first element in the queue
        queueHead.set(node)
        FirstEnqueued
      }
    }

    loop()
  }

  /** Execute all tasks in the queue
    *
    * All tasks, including those that are scheduled during the invocation of this method, either by
    * this a task that is executed or those scheduled from another thread, will be executed in the
    * calling thread.
    */
  def executeTasks(): Unit = {
    // spin while we wait for the tail of the node to resolve
    @tailrec def spin(node: Node): Node = {
      val next = node.get
      if (next ne null) next
      else spin(node)
    }

    @tailrec
    def go(node: Node): Unit =
      if (node ne ClosedNode) {
        try node.runnable.run()
        catch {
          case t @ (NonFatal(_) | _: ControlThrowable) =>
            logger.error(t)("Caught exception in queued task")
        }
        val next = node.get
        if (next eq null) {
          // If we are not the last cell, we will spin until the cons resolves and continue
          if (!queueTail.compareAndSet(node, null))
            go(spin(node))
          // else () // Finished the last node. All done.
        } else go(next)
      }

    val t = queueHead.getAndSet(null)
    if (t ne null)
      go(t)
  }
}

private[blaze] object TaskQueue {
  private val logger = getLogger

  // a node in the task queue with a reference to the next task
  private class Node(val runnable: Runnable) extends AtomicReference[Node]

  /** Result of offering a task to the queue */
  sealed trait Result extends Product with Serializable

  /** The element was enqueued */
  case object Enqueued extends Result

  /** The element was enqueued and was the first element in the queue */
  case object FirstEnqueued extends Result

  /** Failed to enqueue the element because the queue is closed */
  case object Closed extends Result

  // Marker node that when found in tail position says that
  // the queue is closed.
  private val ClosedNode = new Node(new Runnable {
    def run(): Unit = {
      val ex = bug(
        "Illegal state reached! TaskQueue found executing " +
          "a marker node. Please report as a bug.")
      logger.error(ex)("Unexpected state")
    }
  })
}
