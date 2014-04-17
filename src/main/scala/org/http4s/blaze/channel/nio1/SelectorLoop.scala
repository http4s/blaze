package org.http4s.blaze.channel.nio1


import com.typesafe.scalalogging.slf4j.StrictLogging

import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.concurrent.{Future, Promise}

import java.nio.channels._
import java.nio.ByteBuffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.RejectedExecutionException

import org.http4s.blaze.pipeline._
import org.http4s.blaze.channel.nio1.ChannelOps.{ChannelClosed, Incomplete, Complete}
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.channel.nio1.ChannelOps.WriteError
import org.http4s.blaze.util.BufferTools

/**
 * @author Bryce Anderson
 *         Created on 1/20/14
 */

final class SelectorLoop(selector: Selector, bufferSize: Int)
            extends Thread("SelectorLoop") with StrictLogging { thisLoop =>

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
      catch { case t: Exception => logger.error("Caught exception in queued task", t) }
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
              val head = k.attachment().asInstanceOf[NIOHeadStage]

              if (head != null) {
                logger.debug{
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
            case e: IOException =>
              logger.error("IOException in SelectorLoop while performing channel operations", e)
              k.attach(null)
              k.cancel()
          }
        }
      }

    } catch {
      case e: IOException => logger.error("IOException in SelectorLoop while acquiring selector", e)

      case e: ClosedSelectorException =>
        _isClosed = true
        logger.error("Selector unexpectedly closed", e)
        return  // If the selector is closed, no reason to continue the thread.

      case e: Throwable =>
        logger.error("Unhandled exception in selector loop", e)
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
          k.channel().close()
          k.attach(null)
        } catch { case _: IOException => /* NOOP */ }
      }

      selector.close()
    } catch { case t: Throwable => logger.warn("Killing selector resulted in an exception", t) }
  }

  def wakeup(): Unit = selector.wakeup()

  def initChannel(builder: () => LeafBuilder[ByteBuffer], ch: SelectableChannel, ops: SelectionKey => ChannelOps) {
   enqueTask( new Runnable {
      def run() {
        try {
          ch.configureBlocking(false)
          val key = ch.register(selector, 0)

          val head = new NIOHeadStage(ops(key))
          key.attach(head)

          // construct the pipeline
          builder().base(head)

          head.inboundCommand(Command.Connect)
          logger.trace("Started channel.")
        } catch { case e: Throwable => logger.error("Caught error during channel init.", e) }
      }
    })
  }

  private class NIOHeadStage(ops: ChannelOps) extends HeadStage[ByteBuffer] {
    def name: String = "NIO1 ByteBuffer Head Stage"

    ///  channel reading bits //////////////////////////////////////////////

    private val readPromise = new AtomicReference[Promise[ByteBuffer]]

    def readReady(scratch: ByteBuffer) {
      val r = ops.performRead(scratch)

      // if we successfully read some data, unset the interest and complete the promise
      if (r != null) {
        ops.unsetOp(SelectionKey.OP_READ)
        val p = readPromise.getAndSet(null)
        p.complete(r)
      }
    }

    def readRequest(size: Int): Future[ByteBuffer] = {
      logger.trace("NIOHeadStage received a read request")
      val p = Promise[ByteBuffer]

      if (readPromise.compareAndSet(null, p)) {
        ops.setRead()
        p.future
      }
      else Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending read request"))
    }
    
    /// channel write bits /////////////////////////////////////////////////

    @volatile private var writeData: Array[ByteBuffer] = null
    private val writePromise = new AtomicReference[Promise[Any]](null)

    // Will always be called from the SelectorLoop thread
    def writeReady(scratch: ByteBuffer) {

        try ops.performWrite(scratch, writeData) match {
          case Complete =>
            val p = writePromise.get
            writePromise.set(null)
            ops.unsetOp(SelectionKey.OP_WRITE)
            writeData = null
            p.success()

          case Incomplete => /* Need to wait for another go around to try and send more data */

          case ChannelClosed =>
            val p = writePromise.get
            writePromise.set(null)
            writeData = null
            p.tryFailure(EOF)
            ops.close()

          case WriteError(NonFatal(t)) =>
            // seems the channel needs to be shut down
            val p = writePromise.get
            writePromise.set(null)
            writeData = null
            p.tryFailure(t)
            ops.close()

          case WriteError(t) =>
            logger.error("Serious error while performing write. Shutting down Loop", t)
            val p = writePromise.get
            writePromise.set(null)
            writeData = null
            p.tryFailure(t)
            ops.close()
        } catch {
          // Errors shouldn't get to this point, so if they do, close the connection and report it.
          case NonFatal(t) =>
            logger.error("Uncaught exception while performing channel write", t)
            val p = writePromise.getAndSet(null)
            p.tryFailure(t)
            try ops.close()
            catch { case t: Throwable => /* NOOP */ }
        }
    }

    def writeRequest(data: ByteBuffer): Future[Any] = writeRequest(data::Nil)

    override def writeRequest(data: Seq[ByteBuffer]): Future[Any] = {
      val p = Promise[Any]
      if (writePromise.compareAndSet(null, p)) {
        val writes = data.toArray
        if (!BufferTools.checkEmpty(writes)) {  // Non-empty buffer
          writeData = writes
          ops.setWrite()
          p.future
        } else {                                // Empty buffer, just return success
          writePromise.set(null)
          writeData = null
          Future.successful()
        }
      } else {
        Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending write request"))
      }
    }
    
    /// set the channel interests //////////////////////////////////////////

    override protected def stageShutdown(): Unit = {
      super.stageShutdown()
      ops.close()
    }
  }

}
