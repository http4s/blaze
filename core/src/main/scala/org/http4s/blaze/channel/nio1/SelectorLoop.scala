package org.http4s.blaze.channel.nio1


import org.http4s.blaze.pipeline.{Command => Cmd}
import org.http4s.blaze.pipeline._
import org.http4s.blaze.channel.BufferPipelineBuilder

import scala.annotation.tailrec
import scala.util.control.NonFatal
import java.nio.channels._
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.RejectedExecutionException

import org.log4s.getLogger


final class SelectorLoop(id: String, selector: Selector, bufferSize: Int) extends Thread(id) { thisLoop =>

  private[this] val logger = getLogger

  // a node in the task queue with a reference to the next task
  private class Node(val runnable: Runnable) extends AtomicReference[Node]

  // a reference to the latest added Node
  private val queueHead = new AtomicReference[Node](null)
  // a reference to the first added Node
  private val queueTail = new AtomicReference[Node](null)

  @volatile private var _isClosed = false

  /** Signal to the [[SelectorLoop]] that it should close */
  def close(): Unit = {
    logger.info(s"Shutting down SelectorLoop ${getName()}")
    _isClosed = true
    selector.wakeup()
    ()
  }

  @inline
  final def executeTask(r: Runnable): Unit =  {
    if (Thread.currentThread() == thisLoop) r.run()
    else enqueueTask(r)
  }

  @deprecated("Renamed to enqueueTask", "0.12.8")
  def enqueTask(r: Runnable): Unit =
    enqueueTask(r)

  def enqueueTask(r: Runnable): Unit = {
    if (_isClosed) throw new RejectedExecutionException("This SelectorLoop is closed.")

    val node = new Node(r)
    val head = queueHead.getAndSet(node)
    if (head eq null) {
      queueTail.set(node)
      selector.wakeup()
      ()
    } else head.lazySet(node)
  }

  def initChannel(builder: BufferPipelineBuilder, ch: SelectableChannel, mkStage: SelectionKey => NIO1HeadStage): Unit = {
    enqueueTask( new Runnable {
      def run(): Unit = {
        try {
          ch.configureBlocking(false)
          val key = ch.register(selector, 0)

          val head = mkStage(key)
          key.attach(head)

          // construct the pipeline
          builder(NIO1Connection(ch)).base(head)

          head.inboundCommand(Command.Connected)
          logger.debug("Started channel.")
        } catch { case e: Throwable => logger.error(e)("Caught error during channel init.") }
      }
    })
  }

  private def runTasks(): Unit = {
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

  // Main thread method. The loop will break if the Selector loop is closed
  override def run(): Unit = {
    // The scratch buffer is a direct buffer as this will often be used for I/O
    val scratch = ByteBuffer.allocateDirect(bufferSize)

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

      case e: Throwable =>
        logger.error(e)("Unhandled exception in selector loop")
        _isClosed = true
    }

    // If we've made it to here, we've exited the run loop and we need to shut down
    killSelector()
  }

  private def killSelector(): Unit = {
    import scala.collection.JavaConverters._

    try {
      selector.keys().asScala.foreach { k =>
        try {
          val head = k.attachment()
          if (head != null) {
            val stage = head.asInstanceOf[NIO1HeadStage]
            stage.sendInboundCommand(Cmd.Disconnected)
            stage.closeWithError(Cmd.EOF)
          }
          k.channel().close()
          k.attach(null)
        } catch { case _: IOException => /* NOOP */ }
      }

      selector.close()
    } catch { case t: Throwable => logger.warn(t)("Killing selector resulted in an exception") }
  }
}
