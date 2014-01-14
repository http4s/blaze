package blaze.pipeline
package stages
package addons

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.{Promise, Future}

import blaze.util.Execution.directec

/**
 * @author Bryce Anderson
 *         Created on 1/7/14
 */
trait Serializer[I, O] extends MidStage[I, O] {
  
  def maxReadQueue: Int = 0
  def maxWriteQueue: Int = 0

  ////////////////////////////////////////////////////////////////////////

  private val _serializerReadRef = new AtomicReference[Future[O]](null)
  private val _serializerWriteRef = new AtomicReference[Future[Any]](null)

  private val _serializerWaitingReads  = if (maxReadQueue > 0) new AtomicInteger(0) else null
  private val _serializerWaitingWrites = if (maxWriteQueue > 0) new AtomicInteger(0) else null


  ///  channel reading bits //////////////////////////////////////////////

  abstract override def readRequest(size: Int): Future[O] = {
    if (maxReadQueue > 0 && _serializerWaitingReads.incrementAndGet() > maxReadQueue) {
      _serializerWaitingReads.decrementAndGet()
      Future.failed(new Exception(s"$name Stage max read queue exceeded: $maxReadQueue"))
    }
    else  {
      val p = Promise[O]
      val pending = _serializerReadRef.getAndSet(p.future)

      if (pending == null) _serializerDoRead(p, size)  // no queue, just do a read
      else pending.onComplete( _ => _serializerDoRead(p, size))(directec) // there is a queue, need to serialize behind it

      p.future
    }
  }

  private def _serializerDoRead(p: Promise[O], size: Int): Unit = {
    super.readRequest(size).onComplete { t =>
      if (maxReadQueue > 0) _serializerWaitingReads.decrementAndGet()

      _serializerReadRef.compareAndSet(p.future, null)  // don't hold our reference if the queue is idle
      p.complete(t)
    }(directec)
  }

  ///  channel writing bits //////////////////////////////////////////////

  abstract override def writeRequest(data: O): Future[Any] = {
    if (maxWriteQueue > 0 && _serializerWaitingWrites.incrementAndGet() > maxWriteQueue) {
      _serializerWaitingWrites.decrementAndGet()
      Future.failed(new Exception(s"$name Stage max write queue exceeded: $maxReadQueue"))
    }
    else {
      val p = Promise[Any]
      val pending = _serializerWriteRef.getAndSet(p.future)

      if (pending == null) _serializerDoWrite(p, data)  // no queue, just do a read
      else pending.onComplete( _ => _serializerDoWrite(p, data))(directec) // there is a queue, need to serialize behind it

      p.future
    }
  }

  private def _serializerDoWrite(p: Promise[Any], data: O): Unit = {
    super.writeRequest(data).onComplete { t =>
      if (maxWriteQueue > 0) _serializerWaitingWrites.decrementAndGet()

      _serializerWriteRef.compareAndSet(p.future, null)  // don't hold our reference if the queue is idle
      p.complete(t)
    }(directec)
  }

  /// channel Seq[O] writing bits ////////////////////////////////////////

  override def writeRequest(data: Seq[O]): Future[Any] = {
    if (maxWriteQueue > 0 && _serializerWaitingWrites.incrementAndGet() > maxWriteQueue) {
      _serializerWaitingWrites.decrementAndGet()
      Future.failed(new Exception(s"$name Stage max write queue exceeded: $maxReadQueue"))
    }
    else {
      val p = Promise[Any]
      val pending = _serializerWriteRef.getAndSet(p.future)

      if (pending == null) _serializerDoSeqWrite(p, data)  // no queue, just do a read
      else pending.onComplete( _ => _serializerDoSeqWrite(p, data))(directec) // there is a queue, need to serialize behind it

      p.future
    }
  }

  private def _serializerDoSeqWrite(p: Promise[Any], data: Seq[O]): Unit = {
    super.writeRequest(data).onComplete { t =>
      if (maxWriteQueue > 0) _serializerWaitingWrites.decrementAndGet()

      _serializerWriteRef.compareAndSet(p.future, null)  // don't hold our reference if the queue is idle
      p.complete(t)
    }(directec)
  }
}

