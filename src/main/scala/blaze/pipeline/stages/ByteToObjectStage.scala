package blaze.pipeline.stages

import blaze.pipeline.MidStage
import blaze.util.Execution.trampoline

import java.nio.{BufferOverflowException, ByteBuffer}
import scala.concurrent.{Promise, Future}
import scala.util.{Failure, Success}
import blaze.pipeline.Command.Error
import scala.util.control.NonFatal


/**
 * @author Bryce Anderson
 *         Created on 1/13/14
 */
trait ByteToObjectStage[O] extends MidStage[ByteBuffer, O] {

  private var _decodeBuffer: ByteBuffer = null

  /////////////////////////////////////////////////////////////////////////////

  /** Encode objects to buffers
    * @param in object to decode
    * @return sequence of ByteBuffers to pass to the head
    */
  def messageToBuffer(in: O): Seq[ByteBuffer]

  /** Method that decodes ByteBuffers to objects. None reflects not enough data to decode a message
    * Any unused data in the ByteBuffer will be recycled and available for the next read
    * @param in ByteBuffer of immediately available data
    * @return optional message if enough data was available
    */
  def bufferToMessage(in: ByteBuffer): Option[O]

  val maxBufferSize: Int

  /////////////////////////////////////////////////////////////////////////////

  override def writeRequest(data: Seq[O]): Future[Any] = channelWrite(data.flatMap(messageToBuffer))

  def writeRequest(data: O): Future[Any] = channelWrite(messageToBuffer(data))

  def readRequest(size: Int): Future[O] = {
    if (_decodeBuffer != null && _decodeBuffer.hasRemaining) {

      if (_decodeBuffer.position() != 0)  _decodeBuffer.compact().flip()

      try bufferToMessage(_decodeBuffer) match {
        case Some(o) =>
          if (!_decodeBuffer.hasRemaining) _decodeBuffer = null

          return Future.successful(o)

        case None =>  // NOOP, fall through
      }
      catch { case NonFatal(t) => return Future.failed(t) }
    }

    val p = Promise[O]
    decodeLoop(p)
    p.future
  }

  // if we got here, we need more data
  private def decodeLoop(p: Promise[O]): Unit = channelRead().onComplete {
    case Success(b) =>
      // Store all data in _decodeBuffer var
      if (_decodeBuffer != null && _decodeBuffer.hasRemaining) {
        // Need to consolidate the buffer regardless
        val size = _decodeBuffer.remaining() + b.remaining()

        if (_decodeBuffer.capacity() >= size) {
          // enough room so just consolidate
          _decodeBuffer.position(_decodeBuffer.limit())
          _decodeBuffer.limit(size)
          _decodeBuffer.put(b)
          _decodeBuffer.flip()
        }
        else {
          // Need to make a new buffer
          val n = ByteBuffer.allocate(size)
          n.put(_decodeBuffer).put(b).flip()
          _decodeBuffer = n
        }
      } else _decodeBuffer = b

      try bufferToMessage(_decodeBuffer) match {
        case Some(o) =>
          if (!_decodeBuffer.hasRemaining) _decodeBuffer = null
          p.success(o)

        case None => decodeLoop(p)
      }
      catch { case t: Throwable => p.tryFailure(t) }
      finally {
        // Make sure we are not trying to store the previous stages buffer
        // see if we have too large of buffer remaining
        if (maxBufferSize > 0 && _decodeBuffer.remaining() > maxBufferSize) {
          outboundCommand(Error(new BufferOverflowException))
        }

        // Make sure we are not holding onto the ByteBuffer from the inbound stage
        if (_decodeBuffer == b) {
          val b = ByteBuffer.allocate(_decodeBuffer.remaining())
          b.put(_decodeBuffer).flip()
          _decodeBuffer = b
        }
      }


    case Failure(t) => p.failure(t)
  }(trampoline)
}
