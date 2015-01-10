package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.http.http20.Settings.Setting
import org.http4s.blaze.util.BufferTools

trait Http20FrameEncoder {
  import bits._

  def mkDataFrame(data: ByteBuffer, streamId: Int, isLast: Boolean, padding: Byte): Seq[ByteBuffer] = {

    require(streamId > 0, "bad DATA frame stream ID")
    require(padding >= 0 && padding <= 256, "Invalid padding of DATA frame")

    var flags: Int = 0

    if (padding > 0) {
      flags |= Flags.PADDED
    }

    if (isLast) {
      flags |= Flags.END_STREAM
    }

    val headerBuffer = ByteBuffer.allocate(HeaderSize + (if (padding > 0) 1 else 0))

    writeFrameHeader(data.remaining() + padding, FrameTypes.DATA, flags.toByte, streamId, headerBuffer)

    if (padding > 0) headerBuffer.put((padding - 1).toByte)

    headerBuffer.flip()

    headerBuffer::data::paddedTail(padding - 1)
  }

  def mkHeaderFrame(headerData: ByteBuffer,
                    streamId: Int,
                    priority: Option[Priority],
                    end_headers: Boolean,
                    end_stream: Boolean,
                    padding: Int): Seq[ByteBuffer] = {

    require(streamId > 0, "bad HEADER frame stream ID")
    require(padding >= 0 && padding <= 256, "Invalid padding of HEADER frame")

    var flags: Int = 0;
    var size1 = HeaderSize;

    if (padding > 0) {
      size1 += 1          // padding byte
      flags |= Flags.PADDED
    }

    if (priority.nonEmpty) {
      size1 += 4 + 1      // stream dep and weight
      flags |= Flags.PRIORITY
    }

    if (end_headers) flags |= Flags.END_HEADERS
    if (end_stream)  flags |= Flags.END_STREAM

    val header = BufferTools.allocate(size1)
    writeFrameHeader(size1 - HeaderSize + headerData.remaining(), FrameTypes.HEADERS, flags.toByte, streamId, header)

    if (padding > 0) header.put((padding - 1).toByte)

    priority match {
      case Some(p) => writePriority(p, header)
      case None    => // NOOP
    }

    header.flip()

    header::headerData::paddedTail(padding - 1)
  }

  def mkPriorityFrame(streamId: Int, priority: Priority): ByteBuffer = {
    require(streamId > 0, "Invalid streamID for PRIORITY frame")

    val size = 5

    val buffer = BufferTools.allocate(HeaderSize + size)
    writeFrameHeader(size, FrameTypes.PRIORITY, 0, streamId, buffer)

    writePriority(priority, buffer)
    buffer.flip()

    buffer
  }

  def mkRstStreamFrame(streamId: Int, errorCode: Int): ByteBuffer = {
    require(streamId > 0, "Invalid RST_STREAM stream ID")

    val size = 4

    val buffer = BufferTools.allocate(HeaderSize + size)
    writeFrameHeader(size, FrameTypes.RST_STREAM, 0, streamId, buffer)
    buffer.putInt(errorCode)
    buffer.flip()

    buffer
  }

  def mkSettingsFrame(ack: Boolean, settings: Seq[Setting]): ByteBuffer = {
    require(!ack || settings.isEmpty, "Setting acknowledgement must be empty")

    val size = settings.length * 6

    val buffer = BufferTools.allocate(HeaderSize + size)
    val flags = if (ack) Flags.ACK else 0

    writeFrameHeader(size, FrameTypes.SETTINGS, flags.toByte, 0, buffer)

    if (!ack) settings.foreach { case Setting(k,v) => buffer.putShort(k.toShort).putInt(v.toInt) }

    buffer.flip()
    buffer
  }

  def mkPushPromiseFrame(streamId: Int,
                        promiseId: Int,
                      end_headers: Boolean,
                          padding: Int,
                     headerBuffer: ByteBuffer): Seq[ByteBuffer] = {

    require(streamId != 0, "Invalid StreamID for PUSH_PROMISE frame")
    require(promiseId != 0 && promiseId % 2 == 0, "Invalid StreamID for PUSH_PROMISE frame")
    require(0 <= padding && padding <= 256, "Invalid padding of HEADER frame")

    var size = 4;
    var flags = 0;

    if (end_headers) flags |= Flags.END_HEADERS

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
    val size = 8
    require(data.length == size, "Ping data must be 8 bytes long")

    val flags = if (ack) Flags.ACK else 0

    val buffer = ByteBuffer.allocate(HeaderSize + size)
    writeFrameHeader(size, FrameTypes.PING, flags.toByte, 0x0, buffer)
    buffer.put(data)
          .flip()

    buffer
  }

  def mkGoAwayFrame(lastStreamId: Int, error: Long, debugData: ByteBuffer): Seq[ByteBuffer] = {
    require(lastStreamId > 0, "Invalid last stream id for GOAWAY frame")
    val size = 8

    val buffer = BufferTools.allocate(HeaderSize + size)
    writeFrameHeader(size + debugData.remaining(), FrameTypes.GOAWAY, 0x0, 0x0, buffer)
    buffer.putInt(lastStreamId & Masks.int31)
          .putInt(error.toInt)
          .flip()

    buffer::debugData::Nil
  }

  def mkWindowUpdateFrame(streamId: Int, increment: Int): ByteBuffer = {
    require(streamId >= 0, "Invalid stream ID for WINDOW_UPDATE")
    require( 0 < increment && increment <= Integer.MAX_VALUE, "Invalid stream increment for WINDOW_UPDATE")

    val size = 4

    val buffer = BufferTools.allocate(HeaderSize + size)
    writeFrameHeader(size, FrameTypes.WINDOW_UPDATE, 0x0, streamId, buffer)
    buffer.putInt(Masks.int31 & increment)
          .flip()

    buffer
  }

  def mkContinuationFrame(streamId: Int, end_headers: Boolean, headerBuffer: ByteBuffer): Seq[ByteBuffer] = {
    require(streamId > 0, "Invalid stream ID for CONTINUATION frame")
    val flag = if (end_headers) Flags.END_HEADERS else 0x0

    val buffer = BufferTools.allocate(HeaderSize)
    writeFrameHeader(headerBuffer.remaining(), FrameTypes.CONTINUATION, flag.toByte, streamId, buffer)
    buffer.flip()

    buffer::headerBuffer::Nil
  }

  //////////////////////////////////////////////////////////////////////////////////////////

  private def writePriority(p: Priority, buffer: ByteBuffer): Unit = {
    buffer.putInt(p.dependentStreamId | (if (p.exclusive) Masks.exclsive else 0))
    buffer.put(((p.priority - 1) & 0xff).toByte)
  }

  private def paddedTail(padBytes: Int): List[ByteBuffer] = {
    if (padBytes > 0) BufferTools.allocate(padBytes)::Nil
    else             Nil
  }

  private def writeFrameHeader(length: Int, frameType: Byte, flags: Byte, streamdId: Int, buffer: ByteBuffer): Unit = {
    buffer.put((length >>> 16 & 0xff).toByte)
          .put((length >>> 8  & 0xff).toByte)
          .put((length        & 0xff).toByte)
          .put(frameType)
          .put(flags)
          .putInt(streamdId & Masks.STREAMID)
  }
}
