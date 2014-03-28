package org.http4s.blaze.util

import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import com.typesafe.scalalogging.slf4j.Logging


/**
 * @author Bryce Anderson
 *         Created on 2/2/14
 *
 */


/** Low resolution execution scheduler
  *
  * @note The ideas for [[org.http4s.blaze.util.TickWheelExecutor]] is based off of loosely came from the
  * Akka scheduler, which was based on the Netty HashedWheelTimer which was in term
  * based on concepts in <a href="http://cseweb.ucsd.edu/users/varghese/">George Varghese</a>
  * and Tony Lauck's paper <a href="http://cseweb.ucsd.edu/users/varghese/PAPERS/twheel.ps.Z">'Hashed
  * and Hierarchical Timing Wheels: data structures to efficiently implement a
  * timer facility'</a>
  *
  * @constructor primary constructor which immediately spins up a thread and begins ticking
  *
  * @param wheelSize number of spokes on the wheel. Each tick, the wheel will advance a spoke
  * @param resolution duration between ticks
  */
class TickWheelExecutor(wheelSize: Int = 512, resolution: Duration = 100.milli) extends Logging {

  @volatile private var currentTick = 0
  @volatile private var alive = true

  private val tickMilli = resolution.toMillis
  private val _tickInv = 1.0/tickMilli.toDouble

  private val clockFace: Array[Bucket] = {
    val arr = new Array[Bucket](wheelSize)
    0 until wheelSize foreach { i => arr(i) = new Bucket }
    arr
  }

  /////////////////////////////////////////////////////
  // new Thread that actually runs the execution.

  private val thread = new Thread {
    override def run() {
      cycle()
    }
  }

  thread.start()


  /////////////////////////////////////////////////////

  def shutdown(): Unit = {
    alive = false
  }

  // Execute directly on this worker thread. ONLY for QUICK tasks...
  def schedule(r: Runnable, timeout: Duration): Cancellable = {
    schedule(r, Execution.directec, timeout)
  }

  def schedule(r: Runnable, ec: ExecutionContext, timeout: Duration): Cancellable = {
    if (!timeout.isFinite()) sys.error(s"Cannot submit infinite duration delays!")
    else if (alive) {
      val exp = timeout.toMillis
      if (exp > 0) getBucket(exp).add(r, ec, exp + System.currentTimeMillis())
      else {  // we can submit the task right now! Not sure why you would want to do this...
        try ec.execute(r)
        catch { case NonFatal(t) => onNonFatal(t) }
        Cancellable.finished
      }
    }
    else sys.error("TickWheelExecutor is shutdown")
  }

  @tailrec
  private def cycle(): Unit = {
    val i = currentTick
    val time = System.currentTimeMillis()
    currentTick = (i + 1) % wheelSize

    clockFace(i).prune(time) // Remove canceled and submit expired tasks

    if (alive) {
      // Make up for execution time, unlikely to be significant
      val left = tickMilli - (System.currentTimeMillis() - time)
      if (left > 0) Thread.sleep(left)
      cycle()
    }
    else {  // delete all our buckets so we don't hold any references
      for { i <- 0 until wheelSize} clockFace(i) = null
    }
  }

  protected def onNonFatal(t: Throwable) {
    logger.warn("Non-Fatal Exception caught while executing scheduled task", t)
  }
  
  private def getBucket(duration: Long): Bucket = {
    val i = ((duration*_tickInv).toLong + currentTick) % wheelSize
    clockFace(i.toInt)
  }

  private class Bucket {
    private val lock = new AnyRef
    private var nodes: Node = null

    /** Removes expired and canceled elements from this bucket, placing expired ones in the
      * expiredTasks variable for subsequent execution
      * @param time current system time (in milliseconds)
      */
    def prune(time: Long) {
      var expiredTasks: Node = null

      // we lock the Bucket and collect expired elements
      lock.synchronized {
        var prev: Node = null   // Need to keep track of the previous node
        var current: Node = nodes
        nodes = null

        while (current != null) {
          val i = current
          current = i.next

          if (i.cancelled()) {
            if (prev != null) {
              prev.next = i.next
            }
            i.next = null  // Remove the reference
          }
          else if (i.expiresBy(time)) {
            if (prev != null) {
              prev.next = i.next
            }
            i.next = expiredTasks  // Remove the reference
            expiredTasks = i
          }
          else {   // Didn't get popped, so its now the previous node
            if (prev == null) {   // Need to reset the head
              nodes = i
            }
            prev = i
          }
        }
      }

      // All done pruning and the lock is released. Now we need to execute the expiredTasks
      while(expiredTasks != null) {
        val i = expiredTasks
        expiredTasks = expiredTasks.next
        i.next = null
        try i.run()
        catch { case NonFatal(t) => onNonFatal(t) }
      }
    }
    
    def add(r: Runnable, ec: ExecutionContext, expiration: Long): Cancellable = lock.synchronized {
      nodes = new Node(r, ec, expiration, nodes)
      nodes
    }
  }

  /** A Link in a single linked list which can also be passed to the user as a Cancellable
    * @param r [[java.lang.Runnable]] which will be executed after the expired time
    * @param ec [[scala.concurrent.ExecutionContext]] on which to execute the Runnable
    * @param expiration time in milliseconds after which this Node is expired
    * @param next next Node in the list or null if this is the last element
    */
  private class Node(r: Runnable, ec: ExecutionContext, expiration: Long, var next: Node) extends Cancellable {
    @volatile private var alive = true

    def expiresBy(now: Long): Boolean = now >= expiration

    def cancelled(): Boolean = !alive

    def cancel(): Unit = alive = false

    def run() = ec.execute(r)
  }
}

trait Cancellable {

  def cancel(): Unit

  def cancelled(): Boolean

}

object Cancellable {
  val finished = new Cancellable {
    def cancel(): Unit = {}
    def cancelled(): Boolean = false
  }
}
