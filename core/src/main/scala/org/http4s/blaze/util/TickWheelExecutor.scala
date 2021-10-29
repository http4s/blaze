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
import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import org.http4s.blaze.util.TickWheelExecutor.DefaultWheelSize
import org.log4s.getLogger

/** Low resolution execution scheduler
  *
  * @note
  *   The ideas for [[org.http4s.blaze.util.TickWheelExecutor]] is based off of loosely came from
  *   the Akka scheduler, which was based on the Netty HashedWheelTimer which was in term based on
  *   concepts in <a href="http://cseweb.ucsd.edu/users/varghese/">George Varghese</a> and Tony
  *   Lauck's paper <a href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed and
  *   Hierarchical Timing Wheels: data structures to efficiently implement a timer facility'</a>
  *
  * @constructor
  *   primary constructor which immediately spins up a thread and begins ticking
  *
  * @param wheelSize
  *   number of spokes on the wheel. Each tick, the wheel will advance a spoke
  * @param tick
  *   duration between ticks
  */
class TickWheelExecutor(wheelSize: Int = DefaultWheelSize, val tick: Duration = 200.milli) {
  require(wheelSize > 0, "Need finite size number of ticks")
  require(tick.isFinite && tick.toNanos != 0, "tick duration must be finite")

  // Types that form a linked list with links representing different events
  private sealed trait ScheduleEvent
  private case class Register(node: Node, next: ScheduleEvent) extends ScheduleEvent
  private case class Cancel(node: Node, next: ScheduleEvent) extends ScheduleEvent
  private case object Tail extends ScheduleEvent

  private[this] val logger = getLogger

  @volatile private var alive = true

  private val tickNanos = tick.toNanos
  private val _tickInv = 1.0 / tickNanos.toDouble

  private val head = new AtomicReference[ScheduleEvent](Tail)

  private val clockFace: Array[Bucket] =
    (0 until wheelSize).map(_ => new Bucket()).toArray

  // ///////////////////////////////////////////////////
  // new Thread that actually runs the execution.

  private val thread = new Thread(s"blaze-tick-wheel-executor") {
    override def run(): Unit =
      cycle(System.nanoTime())
  }

  thread.setDaemon(true)
  thread.start()

  // ///////////////////////////////////////////////////

  def shutdown(): Unit =
    alive = false

  private[http4s] def isAlive = alive

  /** Schedule the `Runnable` on the [[TickWheelExecutor]]
    *
    * Execution is performed on the [[TickWheelExecutor]] s thread, so only extremely small tasks
    * should be submitted with this method. timeouts of Inf duration are ignored, timeouts of zero
    * or negative duration are executed immediately on the submitting thread.
    *
    * @param r
    *   `Runnable` to be executed
    * @param timeout
    *   `Duration` to wait before execution
    * @return
    *   a [[Cancellable]]. This is not a `java.util.concurrent.Cancellable`, which is a richer
    *   interface.
    */
  def schedule(r: Runnable, timeout: Duration): Cancelable =
    schedule(r, Execution.directec, timeout)

  /** Schedule the `Runnable` on the [[TickWheelExecutor]]
    *
    * timeouts of Inf duration are ignored, timeouts of zero or negative duration are executed
    * immediately on the submitting thread.
    *
    * @param r
    *   `Runnable` to be executed
    * @param ec
    *   `ExecutionContext` to submit the `Runnable`
    * @param timeout
    *   `Duration` to wait before execution
    * @return
    *   a [[Cancellable]]. This is not a `java.util.concurrent.Cancellable`, which is a richer
    *   interface.
    */
  def schedule(r: Runnable, ec: ExecutionContext, timeout: Duration): Cancelable =
    if (alive)
      if (!timeout.isFinite) // This will never timeout, so don't schedule it.
        Cancelable.NoopCancel
      else {
        val nanos = timeout.toNanos
        if (nanos > 0) {
          val expires = nanos + System.nanoTime()

          val node = new Node(r, ec, expires, null, null)

          def go(): Unit = {
            val h = head.get()
            if (!head.compareAndSet(h, Register(node, h))) go()
          }

          go()
          node
        } else { // we can submit the task right now! Not sure why you would want to do this...
          try ec.execute(r)
          catch { case NonFatal(t) => onNonFatal(t) }
          Cancelable.NoopCancel
        }
      }
    else throw TickWheelExecutor.AlreadyShutdownException

  // Deals with appending and removing tasks from the buckets
  private def handleTasks(): Unit = {
    @tailrec
    def go(task: ScheduleEvent): Unit =
      task match {
        case Cancel(n, nxt) =>
          n.canceled = true
          n.unlink()
          go(nxt)

        case Register(n, nxt) =>
          if (!n.canceled) getBucket(n.expiration).add(n)
          go(nxt)

        case Tail => // NOOP
      }

    val tasks = head.getAndSet(Tail)
    go(tasks)
  }

  @tailrec
  private def cycle(lastTickTime: Long): Unit = {
    handleTasks() // Deal with scheduling and cancellations

    val thisTickTime = System.nanoTime()
    val lastTick = (lastTickTime * _tickInv).toLong
    val thisTick = (thisTickTime * _tickInv).toLong
    val ticks = math.min(thisTick - lastTick, wheelSize.toLong)

    @tailrec
    def go(i: Long): Unit =
      if (i < ticks) { // will do at least one tick
        val ii = ((lastTick + i) % wheelSize).toInt
        clockFace(ii)
          .prune(thisTickTime) // Remove canceled and submit expired tasks from the current spoke
        go(i + 1)
      }
    go(0)

    if (alive) {
      val nextTickTime = (thisTick + 1) * tickNanos
      val sleep = nextTickTime - System.nanoTime()
      if (sleep > 0) Thread.sleep(sleep / 1000000)
      cycle(thisTickTime)
    } else // delete all our buckets so we don't hold any references
      for { i <- 0 until wheelSize } clockFace(i) = null
  }

  protected def onNonFatal(t: Throwable): Unit =
    logger.error(t)("Non-Fatal Exception caught while executing scheduled task")

  private def getBucket(expiration: Long): Bucket = {
    val i = ((expiration * _tickInv).toLong) % wheelSize
    clockFace(i.toInt)
  }

  private class Bucket {
    // An empty cell serves as the head of the linked list
    private val head: Node = new Node(null, null, -1, null, null)

    /** Removes expired and canceled elements from this bucket, executing expired elements
      *
      * @param time
      *   current system time (in nanoseconds)
      */
    def prune(time: Long): Unit = {
      @tailrec
      def checkNext(prev: Node): Unit = {
        val next = prev.next
        if (next ne null)
          if (next.canceled) { // remove it
            logger.error("Tickwheel has canceled node in bucket: shouldn't get here.")
            next.unlink()
          } else if (next.expiresBy(time)) {
            next.run()
            next.unlink()
            checkNext(prev)
          } else checkNext(next) // still valid
      }

      checkNext(head)
    }

    def add(node: Node): Unit =
      node.insertAfter(head)
  }

  /** A Link in a single linked list which can also be passed to the user as a Cancelable
    * @param r
    *   [[java.lang.Runnable]] which will be executed after the expired time
    * @param ec
    *   [[scala.concurrent.ExecutionContext]] on which to execute the Runnable
    * @param expiration
    *   time in nanoseconds after which this Node is expired
    * @param next
    *   next Node in the list or `tailNode` if this is the last element
    */
  final private class Node(
      r: Runnable,
      ec: ExecutionContext,
      val expiration: Long,
      var prev: Node,
      var next: Node,
      var canceled: Boolean = false
  ) extends Cancelable {

    /** Remove this node from its linked list */
    def unlink(): Unit = {
      if (prev != null) // Every node that is in a bucket should have a `prev`
        prev.next = next

      if (next != null)
        next.prev = prev

      prev = null
      next = null
    }

    /** Insert this node immediately after `node` */
    def insertAfter(node: Node): Unit = {
      val n = node.next
      node.next = this

      if (n != null)
        n.prev = this

      this.prev = node
      this.next = n
    }

    def expiresBy(now: Long): Boolean = now >= expiration

    /** Schedule a the TickWheel to remove this node next tick */
    def cancel(): Unit = {
      def go(): Unit = {
        val h = head.get()
        if (!head.compareAndSet(h, Cancel(this, h))) go()
      }
      go()
    }

    def run(): Unit =
      try ec.execute(r)
      catch { case NonFatal(t) => onNonFatal(t) }
  }
}

object TickWheelExecutor {
  object AlreadyShutdownException extends RuntimeException("TickWheelExecutor is shutdown")

  /** Default size of the hash wheel */
  val DefaultWheelSize = 512
}
