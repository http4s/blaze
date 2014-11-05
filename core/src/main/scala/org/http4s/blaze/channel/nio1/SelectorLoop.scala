package org.http4s.blaze.channel.nio1


import scala.annotation.tailrec
import scala.util.control.NonFatal

import java.nio.channels._
import java.nio.ByteBuffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.RejectedExecutionException

import org.http4s.blaze.pipeline._
import org.http4s.blaze.channel.BufferPipelineBuilder
import org.log4s.getLogger

/**
 * @author Bryce Anderson
 *         Created on 1/20/14
 */

final class SelectorLoop(selector: Selector, bufferSize: Int)
            extends Thread("SelectorLoop") { thisLoop =>
  private[this] val logger = getLogger

  private class Node(val runnable: Runnable) extends AtomicReference[Node]

  private val queueHead = new AtomicReference[Node](null)
  private val queueTail = new AtomicReference[Node](null)

  private val scratch = ByteBuffer.allocate(bufferSize)
  @volatile private var _isClosed = false

  def executeTask(r: Runnable) {
    if (Thread.currentThread() == thisLoop) r.run()
    else enqueTask(r)
  }

  def enqueTask(r: Runnable): Unit = {
    if (_isClosed) throw new RejectedExecutionException("This SelectorLoop is closed.")

    val node = new Node(r)
    val head = queueHead.getAndSet(node)
    if (head eq null) {
      queueTail.set(node)
      wakeup()
    } else head.lazySet(node)
  }

  private def runTasks() {
    @tailrec def spin(n: Node): Node = {
      val next = n.get()
      if (next ne null) next
      else spin(n)
    }

    @tailrec
    def go(n: Node): Unit = {
      try n.runnable.run()
      catch { case t: Exception => logger.error(t)("Caught exception in queued task") }
      val next = n.get()
      if (next eq null) {
        // If we are not the last cell, we will spin until the cons resolves and continue
        if (!queueHead.compareAndSet(n, null)) go(spin(n))
        //else () // Finished the last node. All done.
      }
      else go(next)
    }

    val t = queueTail.get()
    if (t ne null) {
      queueTail.lazySet(null)
      go(t)
    }
  }

  // Main thread method. This is just a everlasting loop.
  // TODO: should we plan an escape for this loop?
  override def run() {

    try while(!_isClosed) {
      // Run any pending tasks. These may set interest ops, just compute something, etc.
      runTasks()

    // Block here until some IO event happens or someone adds a task to run and wakes the loop
      if (selector.select() > 0) {
        // We have some new IO operations waiting for us. Process them
        val it = selector.selectedKeys().iterator()

        while(it.hasNext) {
          val k = it.next()
          it.remove()

          try {
            if (k.isValid) {
              val head = k.attachment().asInstanceOf[NIO1HeadStage]

              if (head != null) {
                logger.debug {
                  "selection key interests: " +
                  "write: " +  k.isWritable +
                  ", read: " + k.isReadable }

                val readyOps: Int = k.readyOps()

                if ((readyOps & SelectionKey.OP_READ) != 0) head.readReady(scratch)
                if ((readyOps & SelectionKey.OP_WRITE) != 0) head.writeReady(scratch)
              }
              else {   // Null head. Must be disconnected
                k.cancel()
                logger.warn("Selector key had null attachment. Why is the key still in the ops?")
              }

            }
          } catch {
            case e: CancelledKeyException => /* NOOP */
            case t: Throwable =>
              logger.error(t) {
                if (t.isInstanceOf[IOException]) "IOException while performing channel operations. Closing channel."
                else "Error performing channel operations. Closing channel."
              }

              try {
                val head = k.attachment().asInstanceOf[NIO1HeadStage]
                head.closeWithError(t)
                head.inboundCommand(Command.Error(t))
              } catch {
                case NonFatal(_) => /* NOOP */
                case t: Throwable => logger.error(t)("Fatal error shutting down pipeline")
              }
              k.attach(null)
              k.cancel()
          }
        }
      }

    } catch {
      case e: IOException => logger.error(e)("IOException in SelectorLoop while acquiring selector")
      case e: ClosedSelectorException =>
        _isClosed = true
        logger.error(e)("Selector unexpectedly closed")
        return  // If the selector is closed, no reason to continue the thread.

      case e: Throwable =>
        logger.error(e)("Unhandled exception in selector loop")
        close()
        return
    }
  }

  def close() {
    _isClosed = true
    killSelector()
  }

  @throws[IOException]
  private def killSelector() {
    import scala.collection.JavaConversions._

    try {
      selector.keys().foreach { k =>
        try {
          val head = k.attachment()
          if (head != null) {
            head.asInstanceOf[NIO1HeadStage].sendInboundCommand(Command.Disconnected)
          }
          k.channel().close()
          k.attach(null)
        } catch { case _: IOException => /* NOOP */ }
      }

      selector.close()
    } catch { case t: Throwable => logger.warn(t)("Killing selector resulted in an exception") }
  }

  def wakeup(): Unit = selector.wakeup()

  def initChannel(builder: BufferPipelineBuilder, ch: SelectableChannel, mkStage: SelectionKey => NIO1HeadStage) {
   enqueTask( new Runnable {
      def run() {
        try {
          ch.configureBlocking(false)
          val key = ch.register(selector, 0)

          val head = mkStage(key)
          key.attach(head)

          // construct the pipeline
          builder(NIO1Connection(ch)).base(head)

          head.inboundCommand(Command.Connected)
          logger.trace("Started channel.")
        } catch { case e: Throwable => logger.error(e)("Caught error during channel init.") }
      }
    })
  }
}
