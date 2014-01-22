package blaze.channel.nio1

import java.util.concurrent.ConcurrentLinkedQueue

import scala.annotation.tailrec
import java.nio.channels.{CancelledKeyException, SelectableChannel, SelectionKey, Selector}
import java.nio.ByteBuffer
import scala.concurrent.{Future, Promise}
import blaze.pipeline.{Command, PipelineBuilder, HeadStage}
import blaze.channel.PipeFactory
import scala.util.Try
import java.io.IOException
import com.typesafe.scalalogging.slf4j.Logging

/**
 * @author Bryce Anderson
 *         Created on 1/20/14
 */

final class SelectorLoop(selector: Selector, bufferSize: Int) extends Thread with Logging { self =>

  private val pendingTasks = new ConcurrentLinkedQueue[Runnable]()

  private val scratch = ByteBuffer.allocate(bufferSize)

  def executeTask(r: Runnable) {
    if (Thread.currentThread() == self) r.run()
    else enqueTask(r)
  }

  def enqueTask(r: Runnable): Unit = {
    pendingTasks.add(r)
    wakeup()
  }

  override def run() = loop()

  private def loop() {

    try while(true) {
      // Run any pending tasks. It is theoretically possible for the loop to
      // Tasks are run first to add any pending OPS before waiting on the selector
      @tailrec
      def go(r: Runnable): Unit = if (r != null) {
        try r.run()
        catch { case t: Exception => logger.error("Caught exception in queued task", t) }
        go(pendingTasks.poll())
      }
      go(pendingTasks.poll())

    // Block here for a bit until an operation happens or we are awaken by an operation

      logger.trace("Starting select.")
      selector.select()
      val it = selector.selectedKeys().iterator()


      while(it.hasNext) {
        val k = it.next()
        it.remove()

        try {
          if (k.isValid) {
            val head = k.attachment().asInstanceOf[NIOHeadStage]

            if (head != null) {
              logger.trace{"selection key interests: " +
                "write: " + k.isWritable +
                ", read: " + k.isReadable }

              if (k.isReadable) {
                scratch.clear()
                val rt = head.ops.performRead(scratch)
                if (rt != null){
                  head.ops.unsetOp(SelectionKey.OP_READ)
                  head.completeRead(rt)
                }
              }

              if (k.isWritable) {
                val buffers = head.getWrites()
                assert(buffers != null)

                val t = head.ops.performWrite(buffers)
                if (t != null) {
                  head.ops.unsetOp(SelectionKey.OP_WRITE)
                  head.completeWrite(t)
                }
              }
            }
            else {   // Null head. Must be disconnected
              logger.warn("Selector key had null attachment. Why is the key still in the ops?")
            }

          }
        } catch {
          case e: CancelledKeyException => // NOOP
        }


      }

    } catch {
      case e: IOException =>
        logger.error("IOException in SelectorLoop", e)
        killSelector()

      case e: Throwable =>
        logger.error("Unhandled exception in selector loop", e)
        killSelector()
    }
  }

  def close() {
    killSelector()
  }

  private def killSelector() {
    import scala.collection.JavaConversions._

    selector.keys().foreach { k =>
      k.cancel()
      k.attach(null)
    }

    selector.close()
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

  private class NIOHeadStage(private[SelectorLoop] val ops: ChannelOps) extends HeadStage[ByteBuffer] {
    def name: String = "NIO1 ByteBuffer Head Stage"

    private val writeLock = new AnyRef
    private var writeQueue: Array[ByteBuffer] = null
    private var writePromise: Promise[Any] = null

    @volatile private var _readPromise: Promise[ByteBuffer] = null

    ///  channel reading bits //////////////////////////////////////////////
    
    def completeRead(t: Try[ByteBuffer]) {
      val p = _readPromise
      _readPromise = null
      p.complete(t)
    }

    def readRequest(size: Int): Future[ByteBuffer] = {
      logger.trace("NIOHeadStage received a read request")

      if (_readPromise != null) Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending read request"))
      else {
        _readPromise = Promise[ByteBuffer]
        ops.setOp(SelectionKey.OP_READ)  // Already in SelectorLoop

        _readPromise.future
      }
    }
    
    /// channel write bits /////////////////////////////////////////////////

    def writeRequest(data: ByteBuffer): Future[Any] = writeRequest(data::Nil)

    override def writeRequest(data: Seq[ByteBuffer]): Future[Any] = writeLock.synchronized {
      if (writePromise != null) Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending write request"))
      else {
        writeQueue = data.toArray
        writePromise = Promise[Any]
        ops.setOp(SelectionKey.OP_WRITE)  // Already in SelectorLoop

        writePromise.future
      }
    }

    def getWrites(): Array[ByteBuffer] = writeLock.synchronized(writeQueue)

    def completeWrite(t: Try[Any]): Unit = writeLock.synchronized {
      val p = writePromise
      writePromise = null
      writeQueue = null
      p.complete(t)
    }
    
    /// set the channel interests //////////////////////////////////////////

    override protected def stageShutdown(): Unit = {
      super.stageShutdown()
      ops.close()
    }


  }

}
