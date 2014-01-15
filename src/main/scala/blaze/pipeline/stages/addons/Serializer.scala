package blaze.pipeline
package stages
package addons

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.{Promise, Future}

import java.util.ArrayDeque

import blaze.util.Execution._
import scala.util.{Failure, Success}

/**
 * @author Bryce Anderson
 *         Created on 1/7/14
 */
trait Serializer[I, O] extends MidStage[I, O] {

  // These can be overridden to set the true queue overflow size
  val maxReadQueue: Int = 0
  val maxWriteQueue: Int = 0

  ////////////////////////////////////////////////////////////////////////

  private val _serializerWriteQueue = new ArrayDeque[O]
  @volatile private var _serializerWritePromise: Promise[Any] = null

  private val _serializerReadRef = new AtomicReference[Future[O]](null)
  private val _serializerWaitingReads  = if (maxReadQueue > 0) new AtomicInteger(0) else null

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

  abstract override def writeRequest(data: O): Future[Any] = _serializerWriteQueue.synchronized {
    if (maxWriteQueue > 0 && _serializerWriteQueue.size() > maxWriteQueue) {
      Future.failed(new Exception(s"$name Stage max write queue exceeded: $maxReadQueue"))
    }
    else {
      if (_serializerWritePromise == null) {   // there is no queue!
        _serializerWritePromise = Promise[Any]
        val f = super.writeRequest(data)

        f.onComplete {
          case Success(_) => _checkQueue()
          case f:Failure[_] => _serializerWritePromise.complete(f)
        }(directec)

        f
      }
      else {
        _serializerWriteQueue.offer(data)
        _serializerWritePromise.future
      }


    }
  }

  abstract override def writeRequest(data: Seq[O]): Future[Any] = _serializerWriteQueue.synchronized {
    if (maxWriteQueue > 0 && _serializerWriteQueue.size() > maxWriteQueue) {
      Future.failed(new Exception(s"$name Stage max write queue exceeded: $maxReadQueue"))
    }
    else {
      if (_serializerWritePromise == null) {   // there is no queue!
        _serializerWritePromise = Promise[Any]
        val f = super.writeRequest(data)
        f.onComplete {
          case Success(_) => _checkQueue()
          case f:Failure[_] => _serializerWritePromise.complete(f)
        }(directec)
        f
      }
      else {
        data.foreach(_serializerWriteQueue.offer)
        _serializerWritePromise.future
      }
    }
  }

  private def _checkQueue(): Unit = _serializerWriteQueue.synchronized {
    if (_serializerWriteQueue.isEmpty) _serializerWritePromise = null  // Nobody has written anything
    else {      // stuff to write
      import scala.collection.JavaConversions._
      val f = {
        if (_serializerWriteQueue.size() > 1) {
          val a = _serializerWriteQueue.toVector
          _serializerWriteQueue.clear()

          super.writeRequest(a)
        } else super.writeRequest(_serializerWriteQueue.poll())
      }

      val p = _serializerWritePromise
      _serializerWritePromise = Promise[Any]

      f.onComplete { t =>
        if (t.isSuccess) _checkQueue()
        p.complete(t)
      }(trampoline)
    }
  }
}

