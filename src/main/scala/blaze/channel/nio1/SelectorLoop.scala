package blaze.channel.nio1

import java.util.concurrent.ConcurrentLinkedQueue

import scala.annotation.tailrec
import java.nio.channels.{SelectionKey, Selector}
import java.nio.ByteBuffer
import scala.concurrent.{Future, Promise}
import blaze.pipeline.{Command, PipelineBuilder, HeadStage}
import blaze.channel.PipeFactory
import scala.util.Try
import java.io.IOException

/**
 * @author Bryce Anderson
 *         Created on 1/20/14
 */

final class SelectorLoop(selector: Selector, bufferSize: Int = 4*1024) extends Thread { self =>

  private val pendingTasks = new ConcurrentLinkedQueue[Runnable]()

  private val scratch = ByteBuffer.allocate(bufferSize)

  def executeTask(r: Runnable) {
    if (Thread.currentThread() == self) r.run()
    else queueTask(r)
  }

  def queueTask(r: Runnable): Unit = pendingTasks.add(r)

  @tailrec
  private def loop() {

    // Run any pending tasks. It is theoretically possible for the loop to
    // Tasks are run first to add any pending OPS before waiting on the selector
    @tailrec
    def go(r: Runnable): Unit = if (r != null) {
      r.run()
      go(pendingTasks.poll())
    }
    go(pendingTasks.poll())

    // Block here for a bit until an operation happens or we are awaken by an operation
    try {
      selector.select()
      val it = selector.selectedKeys().iterator()

      while(it.hasNext) {
        val k = it.next()
        it.remove()

        if (k.isValid) {
          val head = k.attachment().asInstanceOf[NIOHeadStage]

          if (k.isReadable) {
            scratch.clear()
            val rt = head.ops.performRead(scratch)
            if (rt == null)  head.setRead() // incomplete, re-register
            else head.completeRead(rt)
          }

          if (k.isWritable) {
            val buffers = head.getWrites()
            if (buffers != null) {
              val t = head.ops.performWrite(buffers)
              if (t == null) head.setWrite()  // incomplete, re-register
              else head.completeWrite(t)
            }
            else ???   // should probably not get here

          }
        }
      }

    } catch { case e: IOException => killSelector() }


    loop()
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

    selector.keys().clear()
  }

  def wakeup(): Unit = selector.wakeup()

  def initChannel(builder: PipeFactory, ops: ChannelOps) {
    ops.ch.configureBlocking(false)
    
    val key = ops.ch.register(selector, 0)
    val head = new NIOHeadStage(key, ops)
    key.attach(head)
    builder(PipelineBuilder(head))

    head.outboundCommand(Command.Connected)
  }

  private class NIOHeadStage(key: SelectionKey, private[SelectorLoop] val ops: ChannelOps) extends HeadStage[ByteBuffer] {
    def name: String = "NIO1 ByteBuffer Head Stage"

    private val writeLock = new AnyRef
    private var writeQueue: Array[ByteBuffer] = null
    private var writePromise: Promise[Any] = Promise[Any]

    @volatile private var _readPromise: Promise[ByteBuffer] = null

    ///  channel reading bits //////////////////////////////////////////////
    
    def completeRead(t: Try[ByteBuffer]) {
      val p = _readPromise
      _readPromise = null
      p.complete(t)
    }

    def readRequest(size: Int): Future[ByteBuffer] = {
        if (_readPromise != null) Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending read request"))
        else {
          _readPromise = Promise[ByteBuffer]

          if (Thread.currentThread() == self) setWrite()  // Already in SelectorLoop
          else {
            queueTask(new Runnable { def run() = setRead() })
            wakeup()
          }
          
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

        if (Thread.currentThread() == self) setWrite()  // Already in SelectorLoop
        else {
          queueTask(new Runnable { def run() = setWrite() })
          wakeup()
        }

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
    
    def setWrite() = setOp(SelectionKey.OP_WRITE)
    def setRead() = setOp(SelectionKey.OP_READ)

    private def setOp(op: Int) {
      val ops = key.interestOps()
      if ((ops & op) != 0) {
        key.interestOps(ops | op)
      }
    }
  }

}
