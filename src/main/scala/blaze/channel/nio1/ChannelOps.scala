package blaze.channel.nio1

import scala.util.Try
import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, SelectableChannel}

/**
 * @author Bryce Anderson
 *         Created on 1/21/14
 */

trait ChannelOps {

  protected def ch: SelectableChannel

  protected def key: SelectionKey

  protected def loop: SelectorLoop

  /** Performs the read operation
    * @param scratch a ByteBuffer which is owned by the SelectorLoop to use as
    *                scratch space until this method returns
    * @return a Try with either a successful ByteBuffer, an error, or null if this operation is not complete
    */
  def performRead(scratch: ByteBuffer): Try[ByteBuffer]

  /** Perform the write operation for this channel
    * @param buffers buffers to be written to the channel
    * @return a Try that is either a Success(Any), a Failure with an appropriate error,
    *         or null if this operation is not complete
    */
  def performWrite(scratch: ByteBuffer, buffers: Array[ByteBuffer]): Boolean

  /** Don't close until the next cycle */
  def close(): Unit = loop.enqueTask(new Runnable {
    def run() = {
      key.interestOps(0)
      key.attach(null)
      ch.close()
    }
  })
  
  def setRead() {
    if (Thread.currentThread() == loop) _readTask.run()  // Already in SelectorLoop
    else loop.enqueTask(_readTask)
  }
  
  private val _readTask = new Runnable {
    def run() { _setOp(SelectionKey.OP_READ) }
  }

  def setWrite() {
    if (Thread.currentThread() == loop) _writeTask.run()  // Already in SelectorLoop
    else loop.enqueTask(_writeTask)
  }

  private val _writeTask = new Runnable {
    def run() { _setOp(SelectionKey.OP_WRITE) }
  }

  def unsetOp(op: Int) {
    if (Thread.currentThread() == loop) _unsetOp(op)  // Already in SelectorLoop
    else loop.enqueTask(new Runnable { def run() = _unsetOp(op) })
  }
//
//  def setOp(op: Int) {
//    if (Thread.currentThread() == loop) _setOp(op)  // Already in SelectorLoop
//    else loop.enqueTask(new Runnable { def run() = _setOp(op) })
//  }

  final private def _unsetOp(op: Int) {
    val ops = key.interestOps()
    if ((ops & op) != 0) {
      key.interestOps(ops & ~op)
    }
  }

  final private def _setOp(op: Int) {
    val ops = key.interestOps()
    if ((ops & op) == 0) {
      key.interestOps(ops | op)
    }
  }
}