package org.http4s.blaze.http.spdy

import org.http4s.blaze.pipeline.Command.Disconnect
import org.http4s.blaze.pipeline.stages.ByteToObjectStage
import java.nio. ByteBuffer
import scala.annotation.switch
import scala.util.control.NonFatal


class Spdy3_1FrameCodec(val maxBufferSize: Int = -1)
      extends ByteToObjectStage[SpdyFrame] with SpdyDecoderMethods with SpdyEncoderMethods {

  def name: String = "Spdy3.1 Frame Codec"
  
  def spdyVersion = 3
  protected val inflater = new SpdyHeaderDecoder
  protected val deflater = new SpdyHeaderEncoder

  /** Encode objects to buffers
    * @param in object to decode
    * @return sequence of ByteBuffers to pass to the head
    */
  override def messageToBuffer(in: SpdyFrame): Seq[ByteBuffer] = in match {
    case f: DataFrame         => encodeData(f)
    case f: SynStreamFrame    => encodeSynStream(f)
    case f: SynReplyFrame     => encodeSynReply(f)
    case f: RstStreamFrame    => encodeRstStream(f)
    case f: SettingsFrame     => encodeSettings(f)
    case f: PingFrame         => encodePing(f)
    case f: GoAwayFrame       => encodeGoAway(f)
    case f: HeadersFrame      => encodeHeaders(f)
    case f: WindowUpdateFrame => encodeWindowUpdate(f)
    case f => sys.error(s"Unknown Spdy frame type: $f")
  }

  /** Method that decodes ByteBuffers to objects. None reflects not enough data to decode a message
    * Any unused data in the ByteBuffer will be recycled and available for the next read
    * @param in ByteBuffer of immediately available data
    * @return optional message if enough data was available
    */
  override def bufferToMessage(in: ByteBuffer): Option[SpdyFrame] = {
    logger.info("Attempting to decode frame: " + in)

    if (in.remaining() < 8) return None

    val len = (in.get(5) & 0xff) << 16 | (in.get(6) & 0xff) << 8 | (in.get(7) & 0xff)
    val frametype = in.getShort(2)

    if (in.remaining() < 8 + len) {
      if (1 >= frametype || frametype >= 9) {  // not a real frame
        logger.info("Received what looks to be an invalid frame. " +
                   s"Request length: $len, frame code: $frametype. Maybe HTTP/1?")
        sendOutboundCommand(Disconnect)
      }

      return None
    }

    // Are we a data frame?
    if ((in.get(0) & Flags.CONTROL) == 0)
      return Some(decodeDataFrame(in))

    logger.debug("Decoding frame type: " + frametype)

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
        logger.error(t)(s"Protocol Error during decoding of frame type $frametype")
        val p = in.position()
        in.position(0)
        dumpFrame(in)
        in.position(p)
        throw t

      case NonFatal(t) =>
        logger.error(t)(s"Error decoding frame type $frametype")
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
