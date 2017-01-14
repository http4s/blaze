package org.http4s.blaze.http

import java.nio.ByteBuffer

import org.http4s.blaze.util.{BufferTools, Execution}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

/** Representation of a HTTP message body */
trait MessageBody {

  /** Get a `Future` which may contain message body data.
    *
    * If no data remains, the `ByteBuffer` will be empty as defined by `ByteBuffer.hasRemaining()`
    */
  def apply(): Future[ByteBuffer]

  /** Accumulate any remaining data.
    *
    * The remainder of the message body will be accumulated into a single buffer. If no data remains,
    * the `ByteBuffer` will be empty as defined by `ByteBuffer.hasRemaining()`
    *
    * @param max maximum bytes to accumulate before resulting in a failed future.
    */
  final def accumulate(max: Int = Int.MaxValue): Future[ByteBuffer] = {
    def go(bytes: Long, acc: ArrayBuffer[ByteBuffer]): Future[ByteBuffer] = {
      apply().flatMap {
        case buff if buff.hasRemaining() =>
          val accumulated = bytes + buff.remaining()
          if (accumulated <= max) go(accumulated, acc += buff)
          else {
            val msg = s"Message body overflowed. Maximum permitted: $max, accumulated (thus far): $accumulated"
            Future.failed(new IllegalStateException(msg))
          }

        case _ => Future.successful(BufferTools.joinBuffers(acc))
      }(Execution.trampoline)
    }
    go(0, new ArrayBuffer)
  }
}

private object MessageBody {
  val emptyMessageBody: MessageBody = new MessageBody {
    override def apply(): Future[ByteBuffer] = BufferTools.emptyFutureBuffer
  }

  def apply(buffer: ByteBuffer): MessageBody = new MessageBody {
    private var buff = buffer
    override def apply(): Future[ByteBuffer] = this.synchronized {
      if (buff.hasRemaining) {
        val b = buff
        buff = BufferTools.emptyBuffer
        Future.successful(b)
      }
      else BufferTools.emptyFutureBuffer
    }
  }
}