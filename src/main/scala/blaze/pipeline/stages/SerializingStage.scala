package blaze.pipeline
package stages

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.{Promise, Future}

import blaze.util.Execution.directec

/**
 * @author Bryce Anderson
 *         Created on 1/7/14
 */
final class SerializingStage[I](val name: String = "BufferHeadStage",
                              maxReadQueue: Int = 0,
                              maxWriteQueue: Int = 0) extends MidStage[I, I] {

  private val readRef = new AtomicReference[Future[I]](null)
  private val writeQueueRef = new AtomicReference[Future[Any]](null)

  private val checkReads = maxReadQueue > 0      // so we don't hit atomics if we don't need too
  private val checkWrites = maxWriteQueue > 0
  
  private val waitingReads  = if (checkReads) new AtomicInteger(0) else null
  private val waitingWrites = if (checkWrites) new AtomicInteger(0) else null


  ///  channel reading bits //////////////////////////////////////////////

  def readRequest(size: Int): Future[I] = {
    if (checkReads && waitingReads.incrementAndGet() > maxReadQueue) {
      waitingReads.decrementAndGet()
      Future.failed(new Exception(s"$name Stage max read queue exceeded: $maxReadQueue"))
    }
    else  {
      val p = Promise[I]
      val pending = readRef.getAndSet(p.future)

      if (pending == null) doRead(p, size)  // no queue, just do a read
      else pending.onComplete( _ => doRead(p, size))(directec) // there is a queue, need to serialize behind it

      p.future
    }
  }

  private def doRead(p: Promise[I], size: Int): Unit = {
    channelRead(size).onComplete { t =>
      if (checkReads) waitingReads.decrementAndGet()

      readRef.compareAndSet(p.future, null)  // don't hold our reference if the queue is idle
      p.complete(t)
    }(directec)
  }
  
  ///  channel writing bits //////////////////////////////////////////////

  override def writeRequest(data: I): Future[Any] = {
    if (checkWrites && waitingWrites.incrementAndGet() > maxWriteQueue) {
      waitingWrites.decrementAndGet()
      Future.failed(new Exception(s"$name Stage max read queue exceeded: $maxReadQueue"))
    }
    else {
      val p = Promise[Any]
      val pending = writeQueueRef.getAndSet(p.future)

      if (pending == null) doWrite(p, data)  // no queue, just do a read
      else pending.onComplete( _ => doWrite(p, data))(directec) // there is a queue, need to serialize behind it

      p.future
    }
  }

  private def doWrite(p: Promise[Any], data: I): Unit = {
    channelWrite(data).onComplete { t =>
      if (checkWrites) waitingWrites.decrementAndGet()

      writeQueueRef.compareAndSet(p.future, null)  // don't hold our reference if the queue is idle
      p.complete(t)
    }(directec)
  }
}

