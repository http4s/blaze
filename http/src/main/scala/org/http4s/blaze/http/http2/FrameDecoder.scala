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

import java.nio.{BufferUnderflowException, ByteBuffer}

import Http2Exception._
import org.http4s.blaze.http.http2.SettingsDecoder.SettingsFrame
import org.http4s.blaze.http.http2.bits.{Flags, Masks}

/* The job of the Http2FrameDecoder is to slice the ByteBuffers. It does
   not attempt to decode headers or perform any size limiting operations */
private class FrameDecoder(localSettings: Http2Settings, listener: FrameListener) {
  import bits._
  import FrameDecoder._

  /** Decode a data frame. */
  final def decodeBuffer(buffer: ByteBuffer): Result =
    if (buffer.remaining < HeaderSize) BufferUnderflow
    else doDecodeBuffer(buffer)

  private[this] def doDecodeBuffer(buffer: ByteBuffer): Result = {
    buffer.mark()
    val len = getLengthField(buffer)
    val frameType = buffer.get()
    val flags = buffer.get()
    val streamId = getStreamId(buffer)

    // This concludes the 9 byte header. The rest is payload

    if (localSettings.maxFrameSize < len)
      Error(FRAME_SIZE_ERROR.goaway(s"HTTP2 packet is too large to handle. Stream: $streamId"))
    else if (frameType != FrameTypes.CONTINUATION && listener.inHeaderSequence) {
      // We are in the middle of some header frames which is a no-go
      val msg =
        s"Received frame type ${hexStr(frameType.toInt)} while in HEADERS sequence"
      Error(PROTOCOL_ERROR.goaway(msg))
    } else if (frameType == FrameTypes.CONTINUATION && !listener.inHeaderSequence)
      // Received a CONTINUATION without preceding HEADERS or PUSH_PROMISE frames
      Error(PROTOCOL_ERROR.goaway(s"Received CONTINUATION frame outside of a HEADERS sequence"))
    else if (buffer.remaining < len) { // We still don't have a full frame
      buffer.reset()
      BufferUnderflow
    } else { // full frame. Decode.
      // set frame sizes in the ByteBuffer and decode
      val oldLimit = buffer.limit()
      val endOfFrame = buffer.position() + len
      buffer.limit(endOfFrame)

      try
        frameType match {
          case FrameTypes.DATA => decodeDataFrame(buffer, streamId, flags)
          case FrameTypes.HEADERS => decodeHeaderFrame(buffer, streamId, flags)
          case FrameTypes.PRIORITY => decodePriorityFrame(buffer, streamId)
          case FrameTypes.RST_STREAM => decodeRstStreamFrame(buffer, streamId)
          case FrameTypes.SETTINGS => decodeSettingsFrame(buffer, streamId, flags)
          case FrameTypes.PUSH_PROMISE =>
            decodePushPromiseFrame(buffer, streamId, flags)
          case FrameTypes.PING => decodePingFrame(buffer, streamId, flags)
          case FrameTypes.GOAWAY => decodeGoAwayFrame(buffer, streamId)
          case FrameTypes.WINDOW_UPDATE =>
            decodeWindowUpdateFrame(buffer, streamId)
          case FrameTypes.CONTINUATION =>
            decodeContinuationFrame(buffer, streamId, flags)

          // this concludes the types established by HTTP/2.0, but it could be an extension
          case code => onExtensionFrame(code, streamId, flags, buffer.slice())
        }
      catch {
        case _: BufferUnderflowException =>
          Error(
            FRAME_SIZE_ERROR.goaway(
              s"Frame type ${hexStr(frameType.toInt)} and size $len underflowed"))
      } finally {
        // reset buffer limits
        buffer.limit(oldLimit)
        buffer.position(endOfFrame)
        ()
      }
    }
  }

  /** Hook method for handling protocol extension
    *
    * https://tools.ietf.org/html/rfc7540#section-5.5
    *
    * @param code
    *   the frame type of this extension frame
    * @param streamId
    *   stream id associated with this extension frame
    * @param flags
    *   the flags associated with this extension frame
    * @param buffer
    *   the payload associated with this extension frame
    * @return
    *   result of handling the message. If this extension frame is of unknown type, it MUST be
    *   ignored by spec.
    */
  def onExtensionFrame(code: Byte, streamId: Int, flags: Byte, buffer: ByteBuffer): Result = {
    val _ = (code, streamId, flags, buffer)
    Continue
  }

  // ////////////// Decoding algorithms ///////////////////////////////////////////////////////////

  // ////////// DATA ///////////////
  // https://tools.ietf.org/html/rfc7540#section-6.1
  private[this] def decodeDataFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Result =
    if (streamId == 0)
      /*
       DATA frames MUST be associated with a stream.  If a DATA frame is
       received whose stream identifier field is 0x0, the recipient MUST
       respond with a connection error (Section 5.4.1) of type
       PROTOCOL_ERROR.
       */
      Error(PROTOCOL_ERROR.goaway("Data frame with stream id 0x0"))
    else {
      // "The entire DATA frame payload is included in flow control,
      // including the Pad Length and Padding fields if present."
      val flowBytes = buffer.remaining

      val r =
        if (!Flags.PADDED(flags)) Continue
        else {
          val padding = buffer.get() & 0xff
          limitPadding(padding, buffer)
        }

      if (!r.success) r
      else
        listener.onDataFrame(streamId, Flags.END_STREAM(flags), buffer.slice(), flowBytes)
    }

  // ////////// HEADERS ///////////////
  private[this] def decodeHeaderFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Result =
    if (streamId == 0)
      Error(PROTOCOL_ERROR.goaway("Headers frame with stream id 0x0"))
    else {
      val padding =
        if (!Flags.PADDED(flags)) 0
        else
          buffer.get() & 0xff

      val priority =
        if (!Flags.PRIORITY(flags)) Priority.NoPriority
        else getPriority(buffer)

      val r = limitPadding(padding, buffer)
      if (!r.success) r
      else
        priority match {
          case Priority.Dependent(dep, _, _) if dep == streamId =>
            Error(PROTOCOL_ERROR.goaway(s"Header stream id ${hexStr(streamId)} depends on itself."))

          case _ =>
            listener.onHeadersFrame(
              streamId,
              priority,
              Flags.END_HEADERS(flags),
              Flags.END_STREAM(flags),
              buffer.slice())
        }
    }

  // ////////// PRIORITY ///////////////
  private[this] def decodePriorityFrame(buffer: ByteBuffer, streamId: Int): Result =
    if (streamId == 0)
      Error(PROTOCOL_ERROR.goaway("Priority frame with stream id 0x0"))
    else if (buffer.remaining != 5) { // Make sure the frame has the right amount of data
      val msg = "Invalid PRIORITY frame size, required 5, received" + buffer.remaining
      Error(FRAME_SIZE_ERROR.rst(streamId, msg))
    } else {
      val priority = getPriority(buffer)
      if (priority.dependentStreamId == streamId)
        Error(PROTOCOL_ERROR.rst(streamId, "Priority frame depends on itself"))
      else
        listener.onPriorityFrame(streamId, priority)
    }

  // ////////// RST_STREAM ///////////////
  private[this] def decodeRstStreamFrame(buffer: ByteBuffer, streamId: Int): Result =
    if (streamId == 0)
      Error(PROTOCOL_ERROR.goaway("RST_STREAM frame with stream id 0x0"))
    else if (buffer.remaining != 4) {
      val msg = "Invalid RST_STREAM frame size. Required 4 bytes, received " + buffer.remaining
      Error(FRAME_SIZE_ERROR.goaway(msg))
    } else {
      val code = buffer.getInt() & Masks.INT32
      listener.onRstStreamFrame(streamId, code)
    }

  // ////////// SETTINGS ///////////////
  private[this] def decodeSettingsFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Result =
    SettingsDecoder.decodeSettingsFrame(buffer, streamId, flags) match {
      case Right(SettingsFrame(settings)) =>
        listener.onSettingsFrame(settings)

      case Left(ex) =>
        Error(ex)
    }

  // ////////// PUSH_PROMISE ///////////////
  private[this] def decodePushPromiseFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Result =
    if (streamId == 0)
      Error(PROTOCOL_ERROR.goaway("PUSH_PROMISE frame with stream id 0x0"))
    else {
      val padding =
        if (!Flags.PADDED(flags)) 0
        else
          buffer.get() & 0xff
      val promisedId = getStreamId(buffer)

      if (promisedId == 0)
        Error(PROTOCOL_ERROR.goaway("PUSH_PROMISE frame with promised stream id 0x0"))
      else if (promisedId == streamId)
        Error(
          PROTOCOL_ERROR.goaway(
            s"PUSH_PROMISE frame with promised stream of the same stream ${hexStr(streamId)}"))
      else {
        val r = limitPadding(padding, buffer)
        if (!r.success) r
        else
          listener.onPushPromiseFrame(
            streamId,
            promisedId,
            Flags.END_HEADERS(flags),
            buffer.slice())
      }
    }

  // ////////// PING ///////////////
  private[this] def decodePingFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Result = {
    val PingSize = 8
    if (streamId != 0)
      Error(PROTOCOL_ERROR.goaway(s"PING frame with stream id ${hexStr(streamId)} != 0x0"))
    else if (buffer.remaining != PingSize) {
      val msg = "Invalid PING frame size. Expected 8, received " + buffer.remaining
      Error(FRAME_SIZE_ERROR.goaway(msg))
    } else {
      val pingBytes = new Array[Byte](PingSize)
      buffer.get(pingBytes)
      listener.onPingFrame(Flags.ACK(flags), pingBytes)
    }
  }

  // ////////// GOAWAY ///////////////
  private[this] def decodeGoAwayFrame(buffer: ByteBuffer, streamId: Int): Result =
    if (streamId != 0)
      Error(PROTOCOL_ERROR.goaway(s"GOAWAY frame with stream id ${hexStr(streamId)} != 0x0."))
    else {
      val lastStream = Flags.DepID(buffer.getInt())
      val code: Long = buffer
        .getInt() & Masks.INT32 // java doesn't have unsigned integers
      val data = new Array[Byte](buffer.remaining)
      buffer.get(data)
      listener.onGoAwayFrame(lastStream, code, data)
    }

  // ////////// WINDOW_UPDATE ///////////////
  private[this] def decodeWindowUpdateFrame(buffer: ByteBuffer, streamId: Int): Result =
    if (buffer.remaining != 4)
      Error(
        FRAME_SIZE_ERROR.goaway(
          s"WindowUpdate with invalid frame size. Expected 4, found ${buffer.remaining}"))
    else {
      val size = buffer.getInt() & Masks.INT31
      if (size != 0) listener.onWindowUpdateFrame(streamId, size)
      else
        Error { // never less than 0 due to the mask above
          val msg = s"WINDOW_UPDATE with invalid update size 0"
          if (streamId == 0)
            PROTOCOL_ERROR.goaway(msg)
          else
            PROTOCOL_ERROR.rst(streamId, msg)
        }
    }

  // ////////// CONTINUATION ///////////////
  private[this] def decodeContinuationFrame(
      buffer: ByteBuffer,
      streamId: Int,
      flags: Byte): Result =
    if (streamId == 0) {
      val msg = s"CONTINUATION frame with invalid stream dependency on 0x0"
      Error(PROTOCOL_ERROR.goaway(msg))
    } else
      listener.onContinuationFrame(streamId, Flags.END_HEADERS(flags), buffer.slice())
}

private object FrameDecoder {

  /** Get the length field of the frame, consuming the bytes from the buffer.
    *
    * @return
    * -1 if the buffer doesn't have 3 bytes for the length field, and the length field otherwise.
    */
  def getLengthField(buffer: ByteBuffer): Int =
    ((buffer.get() & 0xff) << 16) |
      ((buffer.get() & 0xff) << 8) |
      ((buffer.get() & 0xff) << 0)

  /** Get the stream id from the `Buffer` consuming exactly 4 bytes. */
  def getStreamId(buffer: ByteBuffer): Int =
    buffer.getInt() & Masks.STREAMID

  /** Read the priority from the `ByteBuffer` */
  private def getPriority(buffer: ByteBuffer): Priority.Dependent = {
    val rawInt = buffer.getInt()
    val priority = (buffer.get() & 0xff) + 1
    val ex = Flags.DepExclusive(rawInt)
    Priority.Dependent(Flags.DepID(rawInt), ex, priority)
  }

  /** Read the padding length and strip the requisite bytes from the end of the buffer using the
    * `ByteBuffer.limit` method.
    */
  private def limitPadding(padding: Int, buffer: ByteBuffer): MaybeError =
    if (padding == 0) Continue
    else if (buffer.remaining < padding)
      // If the length of the padding is the length of the
      // frame payload or greater, the recipient MUST treat this as a
      // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
      Error(
        PROTOCOL_ERROR.goaway(s"Padding ($padding) exceeds payload length: ${buffer.remaining}"))
    else {
      buffer.limit(buffer.limit() - padding)
      Continue
    }

  // TODO: most everwhere else stream id's are not in hex, so make this consistent
  /** Convert an integer into its hex representation with a preceding '0x' */
  def hexStr(i: Int): String = "0x" + Integer.toHexString(i)
}
