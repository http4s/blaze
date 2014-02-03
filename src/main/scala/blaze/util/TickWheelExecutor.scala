package blaze.util

import scala.concurrent.duration._

import java.util.LinkedList
import scala.collection.mutable.ListBuffer
import scala.annotation.tailrec
import com.typesafe.scalalogging.slf4j.Logging
import scala.util.control.NonFatal
import scala.concurrent.ExecutionContext
import scala.collection.mutable
import java.util

/**
 * @author Bryce Anderson
 *         Created on 2/2/14
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

  private val expiredTasks = new util.ArrayDeque[Node](wheelSize)

  /////////////////////////////////////////////////////
  // new Thread that actually runs the execution.

  new Thread {
    override def run() {
      cycle()
    }
  }.start()


  /////////////////////////////////////////////////////

  def shutdown(): Unit = alive = false

  // Execute directly on this worker thread. ONLY for QUICK tasks...
  def schedule(r: Runnable, timeout: Duration): Cancellable = {
    schedule(r, Execution.directec, timeout)
  }

  def schedule(r: Runnable, ec: ExecutionContext, timeout: Duration): Cancellable = {
    if (alive) {
      val exp = timeout.toMillis
      if (exp > 0) getBucket(exp).add(r, ec, exp)
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

    clockFace(i).prune(time) // Will load all the expired tasks into the expiredTasks Dequeue
    executeTasks()

    val left = tickMilli - (System.currentTimeMillis() - time)    // Make up for execution tome, all 0.1 ms probably

    if (left > 0) Thread.sleep(left)

    if (alive) cycle()
    else {  // delete all our buckets so we don't hold any references
      for { i <- 0 until wheelSize} clockFace(i) = null
    }
  }

  private def executeTasks() {
    var t = expiredTasks.poll()
    while (t != null) {
      try t.run()
      catch { case NonFatal(t) => onNonFatal(t) }
      t = expiredTasks.poll()
    }
  }

  private def onNonFatal(t: Throwable) {
    logger.warn("Non-Fatal Exception caught while executing scheduled task", t)
  }
  
  private def getBucket(duration: Long): Bucket = {
    val i = ((duration*_tickInv).toInt + currentTick) % wheelSize
    // Always have a positive number. If its so long as to make it 
    // negative, it really doesn't matter
    clockFace(math.abs(i))
  }

  private class Bucket {
    private val lock = new AnyRef
    private val list = new LinkedList[Node]

    def prune(time: Long) = {
      lock.synchronized {
        val it = list.iterator()

        while (it.hasNext) {
          val i = it.next()
          if (i.cancelled()) it.remove()
          else if (i.expired(time)) {
            it.remove()
            expiredTasks.offer(i)
          }
        }
      }
    }
    
    def add(r: Runnable, ec: ExecutionContext, expiration: Long): Cancellable = {
      val n = new Node(r, ec, expiration)
      lock.synchronized { list.add(n) }
      n
    }

  }

  private class Node(r: Runnable, ec: ExecutionContext, expiration: Long) extends Cancellable with Runnable {
    @volatile private var alive = true

    def expired(now: Long): Boolean = now >= expiration

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
