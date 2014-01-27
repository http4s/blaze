package blaze.pipeline.stages.spdy

import blaze.pipeline.stages.ByteToObjectStage
import java.nio.{BufferUnderflowException, ByteBuffer}
import scala.annotation.{tailrec, switch}
import blaze.util.ScratchBuffer
import scala.collection.mutable.ListBuffer
import java.nio.charset.StandardCharsets.US_ASCII

/**
 * @author Bryce Anderson
 *         Created on 1/26/14
 */
class SpdyFrameCodec(val maxBufferSize: Int = -1) extends ByteToObjectStage[SpdyFrame] {

  def name: String = "Spdy Frame Codec"

  /** Encode objects to buffers
    * @param in object to decode
    * @return sequence of ByteBuffers to pass to the head
    */
  def messageToBuffer(in: SpdyFrame): Seq[ByteBuffer] = in.encode

  /** Method that decodes ByteBuffers to objects. None reflects not enough data to decode a message
    * Any unused data in the ByteBuffer will be recycled and available for the next read
    * @param in ByteBuffer of immediately available data
    * @return optional message if enough data was available
    */
  def bufferToMessage(in: ByteBuffer): Option[SpdyFrame] = {
    if (in.remaining() < 8) return None

    val len = in.get(5) << 16 | in.get(6) << 8 | in.get(7)

    println("******************************** " + (8 + len) + "   " + in.remaining())

    if (in.remaining() < 8 + len) return None

    // We have a whole frame

    // Are we a data frame?
    if ((in.get(0) & (1<<7)) == 0) return Some(decodeDataFrame(in))

    // We are a control frame
    try {
      val frame: ControlFrame = (in.getShort(2): @switch) match {
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

      Some(frame)
    } catch {
      case t: ProtocolException => throw t
    }
  }

  // All of these decoders work from the initial frame byte meaning the data
  // is all available clear down to the control flag bit
  private def decodeDataFrame(data: ByteBuffer): DataFrame = {
    val id = data.getInt() & Masks.STREAMID
    val finished = data.get() != 0
    val len = data.get() << 16 | data.get() << 8 | data.get()
    val body = ByteBuffer.allocate(len)
    body.put(data)
    body.flip()

    DataFrame(body, id, finished)
  }

  private def decodeSynStream(data: ByteBuffer): SynStreamFrame = {
    data.position(4)
    val fb = data.get()
    val finished = (fb & Flags.FINISHED) != 0
    val unidir = (fb & Flags.UNIDIRECTIONAL) != 0

    val len = data.get() << 16 | data.get() << 8 | data.get()

    if (len < 10)
      throw new ProtocolException("Invalid length of SynStreamFrame: $len")

    val streamid = data.getInt() & Masks.STREAMID
    val associated = data.getInt() & Masks.STREAMID

    val priority = (data.get() & 0xff) >>> 5

    data.position(18)
    val limit = data.limit()
    data.limit(len + 8)    // end position of the compressed header data

    val headers = new SpdyHeaderDecoder(data).decodeHeaders()

    data.limit(limit)
    data.position(8 + len)

    SynStreamFrame(streamid, headers, finished, associated, unidir, priority)
  }

  private def decodeSynReplyFrame(data: ByteBuffer): SynReplyFrame = {
    data.position(4)
    val fb = data.get()
    val finished = (fb & Flags.FINISHED) != 0

    val len = data.get() << 16 | data.get() << 8 | data.get()

    if (len < 10)
      throw new ProtocolException(s"Invalid length of SynStreamFrame: $len")

    val streamid = data.getInt() & Masks.STREAMID

    val lim = data.limit()
    data.limit(data.position() + len)

    val headers = new SpdyHeaderDecoder(data).decodeHeaders()

    SynReplyFrame(streamid, headers, finished)
  }

  private def decodeRstStreamFrame(data: ByteBuffer): RstStreamFrame = {
    data.position(4)

    val tmp = data.getInt
    if (tmp != 8)
      throw new ProtocolException(s"Invalid flags or length for RstStreamFrame: $tmp")

    val streamid = data.getInt & Masks.STREAMID
    val code = data.getInt

    RstStreamFrame(streamid, RstCode(code))
  }

  private def decodeSettingsFrame(data: ByteBuffer): SettingsFrame = {
    data.position(4)
    val clear = (data.get() & 0x1) != 0

    val len = data.get() << 16 | data.get() << 8 | data.get()

    if (len < 4)
      throw new ProtocolException(s"Invalid length of ControlFrame: $len")

    val entryCount = data.getInt

    if (entryCount * 8 != len - 4)
      throw new ProtocolException(s"Invalid entry count: $entryCount")

    val entries = 0.until(entryCount).map{ _ =>
      val flag = data.get()
      val id = data.get() << 16 | data.get() << 8 | data.get()
      val value = new Array[Byte](4)
      data.get(value)
      Setting(flag, SettingID(id), value)
    }

    SettingsFrame(entries, clear)
  }

  private def decodePingFrame(data: ByteBuffer): PingFrame = {
    data.position(5)
    val len = data.get() << 16 | data.get() << 8 | data.get()

    if (len != 4)
      throw new ProtocolException(s"Invalid length of PingFrame: $len")

    val id = data.getInt()
    PingFrame(id)
  }

  private def decodeGoAwayFrame(data: ByteBuffer): GoAwayFrame = {
    data.position(5)
    val len = data.get() << 16 | data.get() << 8 | data.get()

    if (len != 8)
      throw new ProtocolException(s"Invalid length of GoAwayFrame: $len")

    val last = data.getInt()
    val code = data.getInt()

    GoAwayFrame(last, GoAwayCode(code))
  }

  private def decodeHeadersFrame(data: ByteBuffer): HeadersFrame = {
    data.position(4)
    val last = (data.get() & Flags.FINISHED) != 0
    val len = data.get() << 16 | data.get() << 8 | data.get()

    println(s"Length: $len")

    if (len < 4)
      throw new ProtocolException(s"Invalid length of HeadersFrame: $len")

    val streamid = data.getInt()

    val lim = data.limit()
    data.limit(data.position() + len - 4)

    val headers = new SpdyHeaderDecoder(data).decodeHeaders()

    // Reset the data boundaries
    data.limit(lim)

    HeadersFrame(streamid, headers, last)
  }

  def decodeWindowUpdate(data: ByteBuffer): WindowUpdate = {
    data.position(4)
    val len = data.getInt()
    if (len != 8)
      throw new ProtocolException(s"Invalid length for WindowUpdate Frame: $len")

    WindowUpdate(data.getInt, data.getInt)
  }
}
