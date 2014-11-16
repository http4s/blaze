package org.http4s.blaze.util

import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import org.log4s.getLogger



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
  * @param tick duration between ticks
  */
class TickWheelExecutor(wheelSize: Int = 512, tick: Duration = 200.milli) {

  require(wheelSize > 0, "Need finite size number of ticks")
  require(tick.isFinite() && tick.toNanos != 0, "tick duration must be finite")

  private[this] val logger = getLogger

  @volatile private var alive = true

  private val tickMilli = tick.toMillis
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
      cycle(System.currentTimeMillis())
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
      val milidur = timeout.toMillis
      if (milidur > 0) {
        val expires = milidur + System.currentTimeMillis()
        getBucket(expires).add(r, ec, expires)
      }
      else {  // we can submit the task right now! Not sure why you would want to do this...
        try ec.execute(r)
        catch { case NonFatal(t) => onNonFatal(t) }
        Cancellable.finished
      }
    }
    else sys.error("TickWheelExecutor is shutdown")
  }

  @tailrec
  private def cycle(lastTickTime: Long): Unit = {
    val now = System.currentTimeMillis()
    val lastTick = (lastTickTime * _tickInv).toLong
    val thisTick = (now * _tickInv).toLong
    val ticks = math.min(thisTick - lastTick, wheelSize)

    @tailrec
    def go(i: Long): Unit = if (i < ticks) { // will do at least one tick
      val ii = ((lastTick + i) % wheelSize).toInt
      clockFace(ii).prune(now) // Remove canceled and submit expired tasks from the current spoke
      go(i + 1)
    }
    go(0)

    if (alive) {
      // Make up for execution time, unlikely to be significant
      val left = tickMilli - (System.currentTimeMillis() - now)
      if (left > 0) Thread.sleep(left)
      cycle(now)
    }
    else {  // delete all our buckets so we don't hold any references
      for { i <- 0 until wheelSize } clockFace(i) = null
    }
  }

  protected def onNonFatal(t: Throwable) {
    logger.error(t)("Non-Fatal Exception caught while executing scheduled task")
  }
  
  private def getBucket(expiration: Long): Bucket = {
    val i = ((expiration*_tickInv).toLong) % wheelSize
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
        val i = nodes
        nodes = null

        @tailrec
        def go(tail: Node, i: Node): Unit = if (i != null) {
          val n = i.next
          i.next = null
          if (i.isCancelled()) go(tail, n)
          else if (i.expiresBy(time)) {
            i.next = expiredTasks
            expiredTasks = i
            go(tail, n)
          }
          else {  // still valid
            if (tail == null) nodes = i // first element
            else tail.next = i
            go(i, n)
          }
        }
        go(null, i)
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

    def isCancelled(): Boolean = !alive

    def cancel(): Unit = alive = false

    def run() = ec.execute(r)
  }
}

trait Cancellable {

  def cancel(): Unit

  def isCancelled(): Boolean

}

object Cancellable {
  val finished = new Cancellable {
    def cancel(): Unit = {}
    def isCancelled(): Boolean = false
  }
}
