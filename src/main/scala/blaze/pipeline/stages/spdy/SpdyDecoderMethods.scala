package blaze.pipeline.stages.spdy

import java.nio.ByteBuffer
import com.typesafe.scalalogging.slf4j.Logging

/**
 * @author Bryce Anderson
 *         Created on 1/27/14
 */
private[spdy] trait SpdyDecoderMethods { self: Logging =>

  protected def inflater: SpdyHeaderDecoder

  // All of these decoders work from the initial frame byte meaning the data
  // is all available clear down to the control flag bit
  protected def decodeDataFrame(data: ByteBuffer): DataFrame = {
    val id = data.getInt() & Masks.STREAMID
    val finished = data.get() != 0
    val len = data.get() << 16 | data.get() << 8 | data.get()
    val body = ByteBuffer.allocate(len)
    body.put(data)
    body.flip()

    DataFrame(body, id, finished)
  }

  protected def decodeSynStream(data: ByteBuffer): SynStreamFrame = {

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

    val headers = inflater.decodeHeaders(data)

    data.limit(limit)
    data.position(8 + len)

    SynStreamFrame(streamid, headers, finished, associated, unidir, priority)
  }

  protected def decodeSynReplyFrame(data: ByteBuffer): SynReplyFrame = {
    data.position(4)
    val fb = data.get()
    val finished = (fb & Flags.FINISHED) != 0

    val len = data.get() << 16 | data.get() << 8 | data.get()

    if (len < 10)
      throw new ProtocolException(s"Invalid length of SynStreamFrame: $len")
     

    val streamid = data.getInt() & Masks.STREAMID

    val lim = data.limit()
    data.limit(data.position() + len - 4)

    // Decode the headers, reset the limit, and get out of here
    val headers = inflater.decodeHeaders(data)
    data.limit(lim)

    SynReplyFrame(streamid, headers, finished)
  }

  protected def decodeRstStreamFrame(data: ByteBuffer): RstStreamFrame = {
    data.position(4)

    val tmp = data.getInt
    if (tmp != 8)
      throw new ProtocolException(s"Invalid flags or length for RstStreamFrame: $tmp")

    val streamid = data.getInt & Masks.STREAMID
    val code = data.getInt

    RstStreamFrame(streamid, RstCode(code))
  }

  protected def decodeSettingsFrame(data: ByteBuffer): SettingsFrame = {
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

  protected def decodePingFrame(data: ByteBuffer): PingFrame = {
    data.position(5)
    val len = data.get() << 16 | data.get() << 8 | data.get()

    if (len != 4)
      throw new ProtocolException(s"Invalid length of PingFrame: $len")

    val id = data.getInt()
    PingFrame(id)
  }

  protected def decodeGoAwayFrame(data: ByteBuffer): GoAwayFrame = {
    data.position(5)
    val len = data.get() << 16 | data.get() << 8 | data.get()

    if (len != 8)
      throw new ProtocolException(s"Invalid length of GoAwayFrame: $len")

    val last = data.getInt()
    val code = data.getInt()

    GoAwayFrame(last, GoAwayCode(code))
  }

  protected def decodeHeadersFrame(data: ByteBuffer): HeadersFrame = {
    data.position(4)
    val last = (data.get() & Flags.FINISHED) != 0
    val len = data.get() << 16 | data.get() << 8 | data.get()

    if (len < 4)
      throw new ProtocolException(s"Invalid length of HeadersFrame: $len")

    val streamid = data.getInt()

    val lim = data.limit()
    data.limit(data.position() + len - 4)

    val headers = inflater.decodeHeaders(data)

    // Reset the data boundaries
    data.limit(lim)

    HeadersFrame(streamid, headers, last)
  }

  protected def decodeWindowUpdate(data: ByteBuffer): WindowUpdateFrame = {
    data.position(4)
    val len = data.getInt()
    if (len != 8)
      throw new ProtocolException(s"Invalid length for WindowUpdate Frame: $len")

    WindowUpdateFrame(data.getInt, data.getInt)
  }

}
