package blaze.pipeline.stages.spdy

import blaze.pipeline.stages.ByteToObjectStage
import java.nio. ByteBuffer
import scala.annotation.switch
import scala.util.control.NonFatal

/**
 * @author Bryce Anderson
 *         Created on 1/26/14
 */
class SpdyFrameCodec(val maxBufferSize: Int = -1)
      extends ByteToObjectStage[SpdyFrame] with SpdyDecoderMethods {

  def name: String = "Spdy Frame Codec"

  protected val inflater = new SpdyHeaderDecoder
  protected val deflater = new SpdyHeaderEncoder

  /** Encode objects to buffers
    * @param in object to decode
    * @return sequence of ByteBuffers to pass to the head
    */
  def messageToBuffer(in: SpdyFrame): Seq[ByteBuffer] = in match {
    case f: SynReplyFrame => f.encode(deflater)
    case f => f.encode
  }

  /** Method that decodes ByteBuffers to objects. None reflects not enough data to decode a message
    * Any unused data in the ByteBuffer will be recycled and available for the next read
    * @param in ByteBuffer of immediately available data
    * @return optional message if enough data was available
    */
  def bufferToMessage(in: ByteBuffer): Option[SpdyFrame] = {
    logger.info("Attempting to decode frame: " + in)

    if (in.remaining() < 8) return None

    val len = in.get(5) << 16 | in.get(6) << 8 | in.get(7)

    if (in.remaining() < 8 + len) return None

    // Are we a data frame?
    if ((in.get(0) & (1 << 7)) == 0) return Some(decodeDataFrame(in))

    val frametype = in.getShort(2)

    logger.trace("Decoding frame type: " + frametype)

    // We are a control frame
    try {
      val frame: ControlFrame = (frametype: @switch) match {
        case 1 => decodeSynStream(in)
        case 2 => decodeSynReplyFrame(in)
        case 3 => decodeRstStreamFrame(in)
        case 4 => decodeSettingsFrame(in)
        case 6 => decodePingFrame(in)
        case 7 => decodeGoAwayFrame(in)
        case 8 => decodeHeadersFrame(in)
        case 9 => decodeWindowUpdate(in)

        case e => sys.error("Unknown control frame type: " + e)
      }

      logger.trace("Buffer After decode: " + in)

      Some(frame)
    } catch {
      case t: ProtocolException =>
        logger.error(s"Protocol Error during decoding of frame type $frametype", t)
        val p = in.position()
        in.position(0)
        dumpFrame(in)
        in.position(p)
        throw t

      case NonFatal(t) =>
        logger.error(s"Error decoding frame type $frametype", t)
        val p = in.position()
        in.position(0)
        dumpFrame(in)
        in.position(p)
        throw t
    }
  }

  override protected def stageShutdown(): Unit = {
    inflater.close()
    super.stageShutdown()
  }

  private def dumpFrame(buffer: ByteBuffer) {
    val sb = new StringBuilder
    var i = 0
    while (buffer.hasRemaining) {
      sb.append("%02X".format(buffer.get() & 0xff)).append(" ")
      i += 1
      if (i == 4) {
        i = 0
        sb.append("\n")
      }
    }
    logger.info("Buffer Content:\n" + sb.result() + "\n")
  }
}
