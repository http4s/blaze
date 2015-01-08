package org.http4s.blaze.util

import java.util.concurrent.atomic.AtomicReference

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

  private sealed abstract class ScheduleEvent(node: Node) extends AtomicReference[ScheduleEvent](null)
  private case class Register(node: Node) extends ScheduleEvent(node)
  private case class Cancel(node: Node) extends ScheduleEvent(node)

  private[this] val logger = getLogger

  @volatile private var alive = true

  private val tickMilli = tick.toMillis
  private val _tickInv = 1.0/tickMilli.toDouble

  private val tailTaskNode = new Register(null) // Marker node for the tail of the offer queue
  private val head = new AtomicReference[ScheduleEvent](tailTaskNode)

  private val tailNode = new Node(null, null, -1, null) // Marker for bucket queues

  private val clockFace: Array[Bucket] = {
    (0 until wheelSize).map(_ => new Bucket()).toArray
  }

  /////////////////////////////////////////////////////
  // new Thread that actually runs the execution.

  private val thread = new Thread(s"TickWheelExecutor: $wheelSize spokes, $tick interval") {
    override def run() {
      cycle(System.currentTimeMillis())
    }
  }

  thread.setDaemon(true)
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
      val millis = timeout.toMillis
      if (millis > 0) {
        val expires = millis + System.currentTimeMillis()

        val node = new Node(r, ec, expires, null)
        addNodeToTasks(Register(node))
        node
      }
      else {  // we can submit the task right now! Not sure why you would want to do this...
        try ec.execute(r)
        catch { case NonFatal(t) => onNonFatal(t) }
        Cancellable.finished
      }
    }
    else sys.error("TickWheelExecutor is shutdown")
  }

  // Adds the ScheduleEvent to the atomic stack
  private def addNodeToTasks(link: ScheduleEvent): Unit = {
    @tailrec
    def go(): Unit = {
      val h = head.get()
      if (head.compareAndSet(h, link)) link.lazySet(h)
      else go()
    }

    go()
  }

  // Deals with appending and removing tasks from the buckets
  private def handleTasks(): Unit = {

    @tailrec
    def go(task: ScheduleEvent): Unit = {
      if (task ne tailTaskNode) {
        task match {
          case Cancel(n) => n.canceled = true
          case Register(n) =>
            if (!n.canceled) getBucket(n.expiration).add(n)
        }

        @tailrec  // will spin, if needed, until the next link resolves
        def getNext(task: ScheduleEvent): ScheduleEvent = {
          val n = task.get()
          if (n == null) getNext(task)
          else n
        }

        go(getNext(task))
      }
    }

    val task = head.getAndSet(tailTaskNode)
    go(task)
  }

  @tailrec
  private def cycle(lastTickTime: Long): Unit = {

    handleTasks()  // Deal with scheduling and cancellations

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
    // An empty cell serves as the head of the linked list
    private val head: Node = new Node(null, null, -1, tailNode)

    /** Removes expired and canceled elements from this bucket, executing expired elements
      *
      * @param time current system time (in milliseconds)
      */
    def prune(time: Long) {
      @tailrec
      def checkNext(prev: Node): Unit = {
        val next = prev.next
        if (next ne tailNode) {
          if (next.canceled) {   // remove it
            prev.next = next.next
            checkNext(prev)
          }
          else if (next.expiresBy(time)) {
            next.run()
            prev.next = next.next
            checkNext(prev)
          }
          else checkNext(next)  // still valid
        }
      }

      checkNext(head)
    }
    
    def add(node: Node): Unit =  {
      node.next = head.next
      head.next = node
    }
  }

  /** A Link in a single linked list which can also be passed to the user as a Cancellable
    * @param r [[java.lang.Runnable]] which will be executed after the expired time
    * @param ec [[scala.concurrent.ExecutionContext]] on which to execute the Runnable
    * @param expiration time in milliseconds after which this Node is expired
    * @param next next Node in the list or ``tailNode` if this is the last element
    */
  final private class Node(r: Runnable,
                          ec: ExecutionContext,
              val expiration: Long,
                    var next: Node,
                var canceled: Boolean = false) extends Cancellable {

    def expiresBy(now: Long): Boolean = now >= expiration

    def cancel(): Unit = addNodeToTasks(Cancel(this))

    def run() = try ec.execute(r) catch { case NonFatal(t) => onNonFatal(t) }
  }
}

trait Cancellable {
  def cancel(): Unit
}

object Cancellable {
  val finished = new Cancellable {
    def cancel(): Unit = {}
    def isCancelled(): Boolean = false
  }
}
