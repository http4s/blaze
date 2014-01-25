package blaze.channel.nio1

import scala.annotation.tailrec
import java.nio.channels._
import java.nio.ByteBuffer
import scala.concurrent.{Future, Promise}
import blaze.pipeline.{Command, PipelineBuilder, HeadStage}
import blaze.channel.PipeFactory
import java.io.IOException
import com.typesafe.scalalogging.slf4j.StrictLogging
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.RejectedExecutionException

/**
 * @author Bryce Anderson
 *         Created on 1/20/14
 */

final class SelectorLoop(selector: Selector, bufferSize: Int) extends Thread("SelectorLoop") with StrictLogging { self =>

  private class Node(val runnable: Runnable) extends AtomicReference[Node]

  private val queueHead = new AtomicReference[Node](null)
  private val queueTail = new AtomicReference[Node](null)

  private val scratch = ByteBuffer.allocate(bufferSize)
  @volatile private var _isClosed = false

  def executeTask(r: Runnable) {
    if (Thread.currentThread() == self) r.run()
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

    // Block here for a bit until some IO event happens or someone adds a task to run and wakes the loop
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
                logger.debug{"selection key interests: " +
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
          } catch { case e: CancelledKeyException => /* NOOP */ }
        }
      }

    } catch {
      case e: IOException =>
        logger.error("IOException in SelectorLoop", e)

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

  def initChannel(builder: PipeFactory, ch: SelectableChannel, ops: SelectionKey => ChannelOps) {
   enqueTask( new Runnable {
      def run() {
        try {
          ch.configureBlocking(false)
          val key = ch.register(selector, 0)

          val head = new NIOHeadStage(ops(key))
          key.attach(head)

          // construct the pipeline
          builder(PipelineBuilder(head))

          head.inboundCommand(Command.Connected)
          logger.trace("Started channel.")
        } catch { case e: Throwable => logger.error("Caught error during channel init.", e) }
      }
    })
  }

  private class NIOHeadStage(ops: ChannelOps) extends HeadStage[ByteBuffer] {
    def name: String = "NIO1 ByteBuffer Head Stage"

    private val writeQueue = new AtomicReference[Array[ByteBuffer]](null)
    @volatile private var _writePromise: Promise[Any] = null

    @volatile private var _readPromise: Promise[ByteBuffer] = null

    ///  channel reading bits //////////////////////////////////////////////

    def readReady(scratch: ByteBuffer) {
      val r = ops.performRead(scratch)

      // if we successfully read some data, unset the interest and complete the promise
      if (r != null) {
        ops.unsetOp(SelectionKey.OP_READ)
        val p = _readPromise
        _readPromise = null
        p.complete(r)
      }
    }

    def readRequest(size: Int): Future[ByteBuffer] = {
      logger.trace("NIOHeadStage received a read request")

      if (_readPromise != null) Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending read request"))
      else {
        _readPromise = Promise[ByteBuffer]
        //ops.setOp(SelectionKey.OP_READ)  // Already in SelectorLoop
        ops.setRead()

        _readPromise.future
      }
    }
    
    /// channel write bits /////////////////////////////////////////////////

    def writeReady(scratch: ByteBuffer) {
      val buffers = writeQueue.get()
      assert(buffers != null)
      val rt = ops.performWrite(scratch, buffers)

      if (rt != null) {
        ops.unsetOp(SelectionKey.OP_WRITE)
        val p = _writePromise
        _writePromise = null
        writeQueue.lazySet(null)
        p.complete(rt)
      }
    }

    def writeRequest(data: ByteBuffer): Future[Any] = writeRequest(data::Nil)

    override def writeRequest(data: Seq[ByteBuffer]): Future[Any] = {
      val writes = data.toArray
      if (!writeQueue.compareAndSet(null, writes)) Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending write request"))
      else {
        _writePromise = Promise[Any]
        //ops.setOp(SelectionKey.OP_WRITE)  // Already in SelectorLoop
        ops.setWrite()
        _writePromise.future
      }
    }
    
    /// set the channel interests //////////////////////////////////////////

    override protected def stageShutdown(): Unit = {
      super.stageShutdown()
      ops.close()
    }
  }

}
