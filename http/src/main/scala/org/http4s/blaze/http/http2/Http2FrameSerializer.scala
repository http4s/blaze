package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.util.BufferTools

private object Http2FrameSerializer {
  import bits._

  // Override the scala provided `require(pred, => msg)` to avoid the thunks
  private[this] def require(predicate: Boolean, msg: String): Unit = {
    if (!predicate)
      throw new IllegalArgumentException(msg)
  }

  /** Create a DATA frame
    *
    * @param streamId stream id of the associated data frame
    * @param endStream whether to set the END_STREAM flag
    * @param padding number of octets by which to pad the message, with 0 meaning the flag is not set,
    *                1 meaning the flag is set and the pad length field is added, and padding = [2-256]
    *                meaning the flag is set, length field is added, and (padding - 1) bytes (0x00) are
    *                added to the end of the frame.
    * @param data data consisting of the payload
    */
  def mkDataFrame(streamId: Int, endStream: Boolean, padding: Int, data: ByteBuffer): Seq[ByteBuffer] = {
    // TODO: Setting a padding length of 0 is valid and a way to pad exactly 1 byte.
    // See note at the end of https://tools.ietf.org/html/rfc7540#section-6.1

    require(streamId > 0, "bad DATA frame stream ID")
    require(padding >= 0, "Invalid padding of DATA frame")
    require(padding <= 256, "Invalid padding of DATA frame: too much padding") // padding is 1 octet

    var flags: Int = 0

    if (padding > 0) {
      flags |= Flags.PADDED
    }

    if (endStream) {
      flags |= Flags.END_STREAM
    }

    val headerBuffer = ByteBuffer.allocate(HeaderSize + (if (padding > 0) 1 else 0))

    writeFrameHeader(data.remaining() + padding, FrameTypes.DATA, flags.toByte, streamId, headerBuffer)

    if (padding > 0) {
      headerBuffer.put((padding - 1).toByte)
    }

    headerBuffer.flip()

    headerBuffer::data::paddedTail(padding - 1)
  }

  // TODO: document the rest of these.
  def mkHeaderFrame(streamId: Int,
                    priority: Priority,
                    endHeaders: Boolean,
                    endStream: Boolean,
                    padding: Byte,
                    headerData: ByteBuffer): Seq[ByteBuffer] = {

    // TODO: Setting a padding length of 0 is valid and a way to pad exactly 1 byte.
    // See note at the end of https://tools.ietf.org/html/rfc7540#section-6.1

    require(streamId > 0, "bad HEADER frame stream ID")
    require(0 <= padding, "Invalid padding of HEADER frame")

    var flags: Int = 0
    var size1 = HeaderSize

    if (padding > 0) {
      size1 += 1          // padding byte
      flags |= Flags.PADDED
    }

    if (priority.isDefined) {
      size1 += 4 + 1      // stream dep and weight
      flags |= Flags.PRIORITY
    }

    if (endHeaders) flags |= Flags.END_HEADERS
    if (endStream)  flags |= Flags.END_STREAM

    val header = BufferTools.allocate(size1)
    writeFrameHeader(size1 - HeaderSize + headerData.remaining(), FrameTypes.HEADERS, flags.toByte, streamId, header)

    if (padding > 0) header.put((padding - 1).toByte)

    priority match {
      case p: Priority.Dependent => writePriority(p, header)
      case Priority.NoPriority    => // NOOP
    }

    header.flip()

    header::headerData::paddedTail(padding - 1)
  }

  def mkPriorityFrame(streamId: Int, priority: Priority.Dependent): ByteBuffer = {
    require(streamId > 0, "Invalid streamID for PRIORITY frame")

    val size = 5

    val buffer = BufferTools.allocate(HeaderSize + size)
    writeFrameHeader(size, FrameTypes.PRIORITY, 0, streamId, buffer)

    writePriority(priority, buffer)
    buffer.flip()

    buffer
  }

  def mkRstStreamFrame(streamId: Int, errorCode: Long): ByteBuffer = {
    require(streamId > 0, "Invalid RST_STREAM stream ID")
    require(errorCode <= 0xffffffffl, "Invalid error code")

    val size = 4

    val buffer = BufferTools.allocate(HeaderSize + size)
    writeFrameHeader(size, FrameTypes.RST_STREAM, 0, streamId, buffer)
    buffer.putInt((errorCode & 0xffffffff).toInt)
    buffer.flip()

    buffer
  }

  def mkSettingsAckFrame(): ByteBuffer = mkSettingsFrameImpl(true, Seq.empty)

  def mkSettingsFrame(settings: Seq[Setting]): ByteBuffer =
    mkSettingsFrameImpl(false, settings)

  private[this] def mkSettingsFrameImpl(ack: Boolean, settings: Seq[Setting]): ByteBuffer = {
    require(!ack || settings.isEmpty, "Setting acknowledgement must be empty")

    val size = settings.length * 6
    val buffer = BufferTools.allocate(HeaderSize + size)
    val flags = if (ack) Flags.ACK.toInt else 0

    writeFrameHeader(size, FrameTypes.SETTINGS, flags.toByte, 0, buffer)
    settings.foreach { case Setting(k,v) => buffer.putShort(k.toShort).putInt(v.toInt) }

    buffer.flip()
    buffer
  }

  def mkPushPromiseFrame(streamId: Int,
                         promiseId: Int,
                         endHeaders: Boolean,
                         padding: Int,
                         headerBuffer: ByteBuffer): Seq[ByteBuffer] = {

    require(streamId != 0, "Invalid StreamID for PUSH_PROMISE frame")
    require(promiseId != 0 && promiseId % 2 == 0, "Invalid StreamID for PUSH_PROMISE frame")
    require(0 <= padding && padding <= 256, "Invalid padding of HEADER frame")

    var size = 4
    var flags = 0

    if (endHeaders) flags |= Flags.END_HEADERS

    if (padding > 0) {
      flags |= Flags.PADDED
      size += padding
    }

    val buffer = BufferTools.allocate(HeaderSize + (if (padding > 0) 5 else 4))
    writeFrameHeader(size + headerBuffer.remaining(), FrameTypes.PUSH_PROMISE, flags.toByte, streamId, buffer)

    if (padding > 0) buffer.put((padding - 1).toByte)

    buffer.putInt(promiseId)

    buffer.flip()

    buffer::headerBuffer::paddedTail(padding - 1)
  }

  def mkPingFrame(ack: Boolean, data: Array[Byte]): ByteBuffer = {
    val PingSize = 8
    require(data.length == PingSize, "Ping data must be 8 bytes long")

    val flags = if (ack) Flags.ACK.toInt else 0

    val buffer = ByteBuffer.allocate(HeaderSize + PingSize)
    writeFrameHeader(PingSize, FrameTypes.PING, flags.toByte, 0x0, buffer)
    buffer.put(data)
          .flip()

    buffer
  }

  def mkGoAwayFrame(lastStreamId: Int, error: Http2Exception): Seq[ByteBuffer] = {
    val msgString = error.getMessage.getBytes(StandardCharsets.UTF_8)
    mkGoAwayFrame(lastStreamId, error.code, msgString)
  }


  def mkGoAwayFrame(lastStreamId: Int, error: Long, debugData: Array[Byte]): Seq[ByteBuffer] = {
    require(lastStreamId >= 0, "Invalid last stream id for GOAWAY frame")
    val size = 8

    val buffer = BufferTools.allocate(HeaderSize + size)
    writeFrameHeader(size + debugData.length, FrameTypes.GOAWAY, 0x0, 0x0, buffer)
    buffer.putInt(lastStreamId & Masks.INT31)
          .putInt(error.toInt)
          .flip()

    buffer::ByteBuffer.wrap(debugData)::Nil
  }

  def mkWindowUpdateFrame(streamId: Int, increment: Int): ByteBuffer = {
    require(streamId >= 0, "Invalid stream ID for WINDOW_UPDATE")
    require( 0 < increment && increment <= Integer.MAX_VALUE, "Invalid stream increment for WINDOW_UPDATE")

    val size = 4

    val buffer = BufferTools.allocate(HeaderSize + size)
    writeFrameHeader(size, FrameTypes.WINDOW_UPDATE, 0x0, streamId, buffer)
    buffer.putInt(Masks.INT31 & increment)
          .flip()

    buffer
  }

  def mkContinuationFrame(streamId: Int, endHeaders: Boolean, headerBuffer: ByteBuffer): Seq[ByteBuffer] = {
    require(streamId > 0, "Invalid stream ID for CONTINUATION frame")
    val flag = if (endHeaders) Flags.END_HEADERS else 0x0b.toByte
    val buffer = BufferTools.allocate(HeaderSize)

    writeFrameHeader(headerBuffer.remaining(), FrameTypes.CONTINUATION, flag, streamId, buffer)
    buffer.flip()

    buffer::headerBuffer::Nil
  }

  //////////////////////////////////////////////////////////////////////////////////////////

  private[this] def writePriority(p: Priority.Dependent, buffer: ByteBuffer): Unit = {
    buffer.putInt(p.dependentStreamId | (if (p.exclusive) Masks.EXCLUSIVE else 0))
    buffer.put(((p.priority - 1) & 0xff).toByte)
    ()
  }

  private[this] def paddedTail(padBytes: Int): List[ByteBuffer] = {
    if (padBytes > 0) BufferTools.allocate(padBytes)::Nil
    else             Nil
  }

  private[this] def writeFrameHeader(length: Int, frameType: Byte, flags: Byte, streamdId: Int, buffer: ByteBuffer): Unit = {
    buffer.put((length >>> 16 & 0xff).toByte)
          .put((length >>> 8  & 0xff).toByte)
          .put((length        & 0xff).toByte)
          .put(frameType)
          .put(flags)
          .putInt(streamdId & Masks.STREAMID)
    ()
  }
}