/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.util.BufferTools

private[http2] object FrameSerializer {
  import bits._

  // Override the scala provided `require(condition, => msg)` to avoid the thunks
  private[this] def require(condition: Boolean, msg: String): Unit =
    if (!condition)
      throw new IllegalArgumentException(msg)

  /** Create a DATA frame
    *
    * @param streamId
    *   stream id of the associated data frame
    * @param endStream
    *   whether to set the END_STREAM flag
    * @param padding
    *   number of octets by which to pad the message, with 0 meaning the flag is not set, 1 meaning
    *   the flag is set and the pad length field is added, and padding = [2-256] meaning the flag is
    *   set, length field is added, and (padding - 1) bytes (0x00) are added to the end of the
    *   frame.
    * @param data
    *   data consisting of the payload
    */
  def mkDataFrame(
      streamId: Int,
      endStream: Boolean,
      padding: Int,
      data: ByteBuffer): Seq[ByteBuffer] = {
    require(0 < streamId, "bad DATA frame stream id")
    require(0 <= padding && padding <= 256, "Invalid padding of DATA frame")

    val padded = 0 < padding
    var flags = 0x0
    if (padded)
      flags |= Flags.PADDED

    if (endStream)
      flags |= Flags.END_STREAM

    val headerBuffer = ByteBuffer.allocate(HeaderSize + (if (padded) 1 else 0))
    val payloadSize = data.remaining + padding
    writeFrameHeader(payloadSize, FrameTypes.DATA, flags.toByte, streamId, headerBuffer)

    if (padded)
      // padding of 1 is represented by the padding field and no trailing padding
      headerBuffer.put((padding - 1).toByte)

    headerBuffer.flip()
    headerBuffer :: data :: tailPadding(padding - 1)
  }

  def mkHeaderFrame(
      streamId: Int,
      priority: Priority,
      endHeaders: Boolean,
      endStream: Boolean,
      padding: Int,
      headerData: ByteBuffer
  ): Seq[ByteBuffer] = {
    require(0 < streamId, "bad HEADER frame stream id")
    require(0 <= padding, "Invalid padding of HEADER frame")

    val padded = 0 < padding
    var flags = 0x0

    var nonDataSize = 0

    if (padded) {
      nonDataSize += 1 // padding byte
      flags |= Flags.PADDED
    }

    if (priority.isDefined) {
      nonDataSize += 4 + 1 // stream dep and weight
      flags |= Flags.PRIORITY
    }

    if (endHeaders)
      flags |= Flags.END_HEADERS

    if (endStream)
      flags |= Flags.END_STREAM

    val header = BufferTools.allocate(HeaderSize + nonDataSize)
    val payloadSize = nonDataSize + headerData.remaining + (if (padded)
                                                              (padding - 1)
                                                            else 0)
    writeFrameHeader(payloadSize, FrameTypes.HEADERS, flags.toByte, streamId, header)

    if (padded)
      // padding of 1 is represented by the padding field and no trailing padding
      header.put((padding - 1).toByte)

    priority match {
      case p: Priority.Dependent => writePriority(p, header)
      case Priority.NoPriority => // NOOP
    }

    header.flip()
    header :: headerData :: tailPadding(padding - 1)
  }

  def mkPriorityFrame(streamId: Int, priority: Priority.Dependent): ByteBuffer = {
    require(0 < streamId, "Invalid stream id for PRIORITY frame")

    val payloadSize = 5
    val buffer = BufferTools.allocate(HeaderSize + payloadSize)
    writeFrameHeader(payloadSize, FrameTypes.PRIORITY, 0x0, streamId, buffer)

    writePriority(priority, buffer)
    buffer.flip()

    buffer
  }

  def mkRstStreamFrame(streamId: Int, errorCode: Long): ByteBuffer = {
    require(0 < streamId, "Invalid RST_STREAM stream id")
    require(0 <= errorCode && errorCode <= Masks.INT32, s"Invalid error code")

    val payloadSize = 4
    val buffer = BufferTools.allocate(HeaderSize + payloadSize)
    writeFrameHeader(payloadSize, FrameTypes.RST_STREAM, 0x0, streamId, buffer)
    buffer.putInt((errorCode & Masks.INT32).toInt)
    buffer.flip()

    buffer
  }

  def mkSettingsAckFrame(): ByteBuffer = mkSettingsFrameImpl(true, Seq.empty)

  def mkSettingsFrame(settings: Seq[Setting]): ByteBuffer =
    mkSettingsFrameImpl(false, settings)

  private[this] def mkSettingsFrameImpl(ack: Boolean, settings: Seq[Setting]): ByteBuffer = {
    require(!ack || settings.isEmpty, "Setting acknowledgement must be empty")

    val payloadSize = settings.length * 6
    val buffer = BufferTools.allocate(HeaderSize + payloadSize)
    val flags = if (ack) Flags.ACK.toInt else 0x0

    writeFrameHeader(payloadSize, FrameTypes.SETTINGS, flags.toByte, 0, buffer)
    settings.foreach { case Setting(k, v) =>
      buffer.putShort(k.toShort).putInt(v.toInt)
    }

    buffer.flip()
    buffer
  }

  def mkPushPromiseFrame(
      streamId: Int,
      promiseId: Int,
      endHeaders: Boolean,
      padding: Int,
      headerBuffer: ByteBuffer
  ): Seq[ByteBuffer] = {
    require(streamId != 0x0, "Invalid Stream id for PUSH_PROMISE frame")
    require(promiseId != 0x0 && promiseId % 2 == 0, "Invalid Stream id for PUSH_PROMISE frame")
    require(0 <= padding && padding <= 256, "Invalid padding of HEADER frame")

    val padded = 0 < padding
    var flags = 0x0

    if (endHeaders)
      flags |= Flags.END_HEADERS

    if (padded)
      flags |= Flags.PADDED

    // Need4 for the promised stream id, and maybe 1 for the padding size
    val bufferSize = HeaderSize + 4 + (if (padded) 1 else 0)
    val buffer = BufferTools.allocate(bufferSize)
    writeFrameHeader(
      4 + padding + headerBuffer.remaining,
      FrameTypes.PUSH_PROMISE,
      flags.toByte,
      streamId,
      buffer)

    if (padded)
      // padding of 1 is represented by the padding field and no trailing padding
      buffer.put((padding - 1).toByte)

    buffer
      .putInt(promiseId)
      .flip()

    buffer :: headerBuffer :: tailPadding(padding - 1)
  }

  def mkPingFrame(ack: Boolean, data: Array[Byte]): ByteBuffer = {
    val PingSize = 8
    require(data.length == PingSize, "Ping data must be 8 bytes long")

    val flags: Byte = if (ack) Flags.ACK else 0x0
    val buffer = ByteBuffer.allocate(HeaderSize + PingSize)
    writeFrameHeader(PingSize, FrameTypes.PING, flags, 0, buffer)
    buffer
      .put(data)
      .flip()

    buffer
  }

  def mkGoAwayFrame(lastStreamId: Int, error: Http2Exception): ByteBuffer = {
    val msgString = error.getMessage.getBytes(StandardCharsets.UTF_8)
    mkGoAwayFrame(lastStreamId, error.code, msgString)
  }

  def mkGoAwayFrame(lastStreamId: Int, error: Long, debugData: Array[Byte]): ByteBuffer = {
    require(0 <= lastStreamId, "Invalid last stream id for GOAWAY frame")

    val size = 8
    val buffer = BufferTools.allocate(HeaderSize + size + debugData.length)
    writeFrameHeader(size + debugData.length, FrameTypes.GOAWAY, 0x0, 0, buffer)
    buffer
      .putInt(lastStreamId & Masks.INT31)
      .putInt(error.toInt)
      .put(debugData)
      .flip()
    buffer
  }

  def mkWindowUpdateFrame(streamId: Int, increment: Int): ByteBuffer = {
    require(0 <= streamId, "Invalid stream id for WINDOW_UPDATE")
    require(
      0 < increment && increment <= Integer.MAX_VALUE,
      "Invalid stream increment for WINDOW_UPDATE")

    val size = 4
    val buffer = BufferTools.allocate(HeaderSize + size)
    writeFrameHeader(size, FrameTypes.WINDOW_UPDATE, 0x0, streamId, buffer)
    buffer
      .putInt(Masks.INT31 & increment)
      .flip()

    buffer
  }

  def mkContinuationFrame(
      streamId: Int,
      endHeaders: Boolean,
      headerBuffer: ByteBuffer): Seq[ByteBuffer] = {
    require(0 < streamId, "Invalid stream id for CONTINUATION frame")
    val flags: Byte = if (endHeaders) Flags.END_HEADERS else 0x0
    val buffer = BufferTools.allocate(HeaderSize)

    writeFrameHeader(headerBuffer.remaining, FrameTypes.CONTINUATION, flags, streamId, buffer)
    buffer.flip()

    buffer :: headerBuffer :: Nil
  }

  // ////////////////////////////////////////////////////////////////////////////////////////

  private[this] def writePriority(p: Priority.Dependent, buffer: ByteBuffer): Unit = {
    if (p.exclusive)
      buffer.putInt(p.dependentStreamId | Masks.EXCLUSIVE)
    else
      buffer.putInt(p.dependentStreamId)

    buffer.put(((p.priority - 1) & 0xff).toByte)
    ()
  }

  // No need to create new underlying arrays every time since at most it will be 255 bytes of 0's
  private[this] val sharedPadding: ByteBuffer =
    BufferTools.allocate(255).asReadOnlyBuffer()

  private[this] def tailPadding(padBytes: Int): List[ByteBuffer] =
    if (0 < padBytes) {
      val b = sharedPadding.duplicate()
      b.limit(padBytes)
      b :: Nil
    } else Nil

  private[this] def writeFrameHeader(
      length: Int,
      frameType: Byte,
      flags: Byte,
      streamdId: Int,
      buffer: ByteBuffer
  ): Unit = {
    buffer
      .put((length >>> 16 & 0xff).toByte)
      .put((length >>> 8 & 0xff).toByte)
      .put((length & 0xff).toByte)
      .put(frameType)
      .put(flags)
      .putInt(streamdId & Masks.STREAMID)
    ()
  }
}
