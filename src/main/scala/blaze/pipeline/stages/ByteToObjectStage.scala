package blaze.pipeline.stages

import blaze.pipeline.MidStage
import blaze.util.Execution.trampoline
import blaze.pipeline.Command.Error

import java.nio.{BufferOverflowException, ByteBuffer}

import scala.concurrent.{Promise, Future}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal


/**
 * @author Bryce Anderson
 *         Created on 1/13/14
 */
trait ByteToObjectStage[O] extends MidStage[ByteBuffer, O] {

  import blaze.util.BufferTools._

  private var _decodeBuffer: ByteBuffer = null

  /////////////////////////////////////////////////////////////////////////////

  /** Encode objects to buffers
    * @param in object to decode
    * @return sequence of ByteBuffers to pass to the head
    */
  def messageToBuffer(in: O): Seq[ByteBuffer]

  /** Method that decodes ByteBuffers to objects. None reflects not enough data to decode a message
    * Any unused data in the ByteBuffer will be recycled and available for the next read.
    *
    * WARNING: don't count on the underlying array of the ByteBuffer. This uses the slice method, which
    * could preserve access to the buffer, but mess with the various positions.
    *
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
      try {
        val slice = _decodeBuffer.slice()
        val result = bufferToMessage(slice)
        cleanBuffers(slice)

        result match {
          case Some(o) => Future.successful(o)
          case None    => startReadDecode()
        }
      }
      catch { case NonFatal(t) => Future.failed(t) }
    } else startReadDecode()
  }

  private def startReadDecode(): Future[O] = {
    val p = Promise[O]
    readAndDecodeLoop(p)
    p.future
  }

  // if we got here, we need more data
  private def readAndDecodeLoop(p: Promise[O]): Unit = channelRead().onComplete {
    case Success(lineBuffer) =>
      _decodeBuffer = concatBuffers(_decodeBuffer, lineBuffer)

      // Now we slice the buffer, decode, and set the correct position on our internal buffer
      try {
        val slice = _decodeBuffer.slice()
        val result = bufferToMessage(slice)
        cleanBuffers(slice)

        result match {
          case Some(o) =>  p.success(o)
          case None    => readAndDecodeLoop(p)
        }
      }
      catch { case NonFatal(t) =>
        logger.error("Error during decode", t)
        p.tryFailure(t)
      }

    case Failure(t) => p.failure(t)
  }(trampoline)

  /** Maintains the state of the internal _decodeBuffer */
  private def cleanBuffers(slice: ByteBuffer) {
    if (slice.position() > 0) {
      _decodeBuffer.position(_decodeBuffer.position() + slice.position())
    }

    // Make sure we are not trying to store the previous stages buffer
    // see if we have too large of buffer remaining
    if (maxBufferSize > 0 && _decodeBuffer.remaining() > maxBufferSize) {
      outboundCommand(Error(new BufferOverflowException))
    }

    else if (!_decodeBuffer.hasRemaining)  _decodeBuffer = null
  }
}
