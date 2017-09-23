package org.http4s.blaze.http.http2

import java.nio.{BufferUnderflowException, ByteBuffer}

import Http2Exception._
import org.http4s.blaze.http.http2.SettingsDecoder.SettingsFrame
import org.http4s.blaze.http.http2.bits.Masks
import org.http4s.blaze.util.BufferTools


/* The job of the Http2FrameDecoder is to slice the ByteBuffers. It does
   not attempt to decode headers or perform any size limiting operations */
class Http2FrameDecoder(mySettings: Http2Settings, listener: Http2FrameListener) {
  import bits._
  import Http2FrameDecoder._

  /** Decode a data frame. */
  final def decodeBuffer(buffer: ByteBuffer): Http2Result = {
    if (buffer.remaining < HeaderSize) {
      return BufferUnderflow
    }

    buffer.mark()
    val len = getLengthField(buffer)
    val frameType = buffer.get()
    val flags = buffer.get()
    val streamId = getStreamId(buffer)

    // This concludes the 9 byte header. The rest is payload

    if (mySettings.maxFrameSize < len) {
      Error(FRAME_SIZE_ERROR.goaway(s"HTTP2 packet is too large to handle. Stream: $streamId"))
    } else if (listener.inHeaderSequence && frameType != FrameTypes.CONTINUATION) {
    // We are in the middle of some header frames which is a no-go
      val msg = s"Received frame type 0x${Integer.toHexString(frameType.toInt)} while in HEADERS sequence"
      Error(PROTOCOL_ERROR.goaway(msg))
    } else if (buffer.remaining < len) {   // We still don't have a full frame
      buffer.reset()
      BufferUnderflow
    } else { // full frame. Decode.
      // set frame sizes in the ByteBuffer and decode
      val oldLimit = buffer.limit
      val endOfFrame = buffer.position + len
      buffer.limit(endOfFrame)

      try frameType match {
        case FrameTypes.DATA          => decodeDataFrame(buffer, streamId, flags)
        case FrameTypes.HEADERS       => decodeHeaderFrame(buffer, streamId, flags)
        case FrameTypes.PRIORITY      => decodePriorityFrame(buffer, streamId, flags)
        case FrameTypes.RST_STREAM    => decodeRstStreamFrame(buffer, streamId)
        case FrameTypes.SETTINGS      => decodeSettingsFrame(buffer, streamId, flags)
        case FrameTypes.PUSH_PROMISE  => decodePushPromiseFrame(buffer, streamId, flags)
        case FrameTypes.PING          => decodePingFrame(buffer, streamId, flags)
        case FrameTypes.GOAWAY        => decodeGoAwayFrame(buffer, streamId)
        case FrameTypes.WINDOW_UPDATE => decodeWindowUpdateFrame(buffer, streamId)
        case FrameTypes.CONTINUATION  => decodeContinuationFrame(buffer, streamId, flags)

        // this concludes the types established by HTTP/2.0, but it could be an extension
        case code                     => onExtensionFrame(code, streamId, flags, buffer.slice())
      } catch {
        case _: BufferUnderflowException =>
          Error(FRAME_SIZE_ERROR.goaway
          (s"Frame type 0x${Integer.toHexString(frameType.toInt)} and size $len underflowed"))
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
    * @param code the frame type of this extension frame
    * @param streamId stream id associated with this extension frame
    * @param flags the flags associated with this extension frame
    * @param buffer the payload associated with this extension frame
    * @return result of handling the message. If this extension frame is of
    *         unknown type, it MUST be ignored by spec.
    */
  def onExtensionFrame(code: Byte, streamId: Int, flags: Byte, buffer: ByteBuffer): Http2Result =
    Continue

  //////////////// Decoding algorithms ///////////////////////////////////////////////////////////

  //////////// DATA ///////////////
  // https://tools.ietf.org/html/rfc7540#section-6.1
  private def decodeDataFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {
    if (streamId == 0) {
      /*
       DATA frames MUST be associated with a stream.  If a DATA frame is
       received whose stream identifier field is 0x0, the recipient MUST
       respond with a connection error (Section 5.4.1) of type
       PROTOCOL_ERROR.
       */
      return Error(PROTOCOL_ERROR.goaway("Data frame with streamID 0x0"))
    }

    // "The entire DATA frame payload is included in flow control,
    // including the Pad Length and Padding fields if present."
    val flowBytes = buffer.remaining

    if (Flags.PADDED(flags)) {
      val padding = buffer.get() & 0xff
      val r = limitPadding(padding, buffer)
      if (!r.success) {
        return r
      }
    }

    listener.onDataFrame(streamId, Flags.END_STREAM(flags), buffer.slice(), flowBytes)
  }

  //////////// HEADERS ///////////////
  private def decodeHeaderFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {
    if (streamId == 0) {
      return Error(PROTOCOL_ERROR.goaway("Headers frame with streamID 0x0"))
    }

    val padding = if (!Flags.PADDED(flags)) 0 else { buffer.get() & 0xff }

    val priority =
      if (!Flags.PRIORITY(flags)) Priority.NoPriority
      else getPriority(buffer)

    val r = limitPadding(padding, buffer)
    if (!r.success) {
      return r
    }

    priority match {
      case Priority.Dependant(dep, _, _) if dep == streamId =>
        Error(PROTOCOL_ERROR.goaway(s"Header stream depends on itself (id $streamId)"))

      case _ =>
      listener.onHeadersFrame(
        streamId, priority, Flags.END_HEADERS(flags),
        Flags.END_STREAM(flags), buffer.slice())
    }
  }


  //////////// PRIORITY ///////////////
  private def decodePriorityFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {
    if (streamId == 0) {
      return Error(PROTOCOL_ERROR.goaway("Priority frame with streamID 0x0"))
    }

    if (buffer.remaining != 5) {    // Make sure the frame has the right amount of data
      val msg = "Invalid PRIORITY frame size, required 5, received" + buffer.remaining
      return Error(FRAME_SIZE_ERROR.rst(streamId, msg))
    }

    val priority = getPriority(buffer)
    if (priority.dependentStreamId == streamId) {
      return Error(PROTOCOL_ERROR.rst(streamId, "Priority frame depends on itself"))
    }

    listener.onPriorityFrame(streamId, priority)
  }

  private def getPriority(buffer: ByteBuffer): Priority.Dependant = {
    val rawInt = buffer.getInt()
    val priority = (buffer.get() & 0xff) + 1
    val ex = Flags.DepExclusive(rawInt)
    Priority.Dependant(Flags.DepID(rawInt), ex, priority)
  }

  //////////// RST_STREAM ///////////////
  private def decodeRstStreamFrame(buffer: ByteBuffer, streamId: Int): Http2Result = {
    if (streamId == 0) {
      return Error(PROTOCOL_ERROR.goaway("RST_STREAM frame with stream ID 0"))
    } else if (buffer.remaining != 4) {
      val msg = "Invalid RST_STREAM frame size. Required 4 bytes, received " + buffer.remaining
      return Error(FRAME_SIZE_ERROR.goaway(msg))
    }

    val code = buffer.getInt() & 0xffffffffl
    listener.onRstStreamFrame(streamId, code)
  }

  //////////// SETTINGS ///////////////
  private def decodeSettingsFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {
    SettingsDecoder.decodeSettingsFrame(buffer, streamId, flags) match {
      case Right(SettingsFrame(isAck, settings)) =>
        listener.onSettingsFrame(isAck, settings)

      case Left(ex) =>
        Error(ex)
    }
  }

  //////////// PUSH_PROMISE ///////////////
  private def decodePushPromiseFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {
    if (streamId == 0) {
      return Error(PROTOCOL_ERROR.goaway("PUSH_PROMISE frame with streamID 0x0"))
    }

    val padding = if (!Flags.PADDED(flags)) 0 else { buffer.get() & 0xff }
    val promisedId = buffer.getInt() & Masks.STREAMID

    if (promisedId == 0) {
      return Error(PROTOCOL_ERROR.goaway("PUSH_PROMISE frame with promised stream id 0x0"))
    } else if (promisedId == streamId) {
      return Error(PROTOCOL_ERROR.goaway(
        s"PUSH_PROMISE frame with promised stream of the same stream $streamId"))
    }

    val r = limitPadding(padding, buffer)
    if (!r.success) r
    else {
      listener.onPushPromiseFrame(streamId, promisedId, Flags.END_HEADERS(flags), buffer.slice())
    }
  }

  //////////// PING ///////////////
  private def decodePingFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {
    val PingSize = 8
    if (streamId != 0) {
      return Error(PROTOCOL_ERROR.goaway(s"PING frame with streamID != 0x0. Id: $streamId"))
    } else if (buffer.remaining != PingSize) {
      val msg = "Invalid PING frame size. Expected 4, received " + buffer.remaining
      return Error(FRAME_SIZE_ERROR.goaway(msg))
    }

    val pingBytes = new Array[Byte](PingSize)
    buffer.get(pingBytes)
    listener.onPingFrame(Flags.ACK(flags), pingBytes)
  }

  //////////// GOAWAY ///////////////
  private def decodeGoAwayFrame(buffer: ByteBuffer, streamId: Int): Http2Result = {
    if (streamId != 0) {
      return Error(PROTOCOL_ERROR.goaway(s"GOAWAY frame with $streamId != 0x0."))
    }

    val lastStream = Flags.DepID(buffer.getInt)
    val code: Long = buffer.getInt() & 0xffffffffl   // java doesn't have unsigned integers
    val data = new Array[Byte](buffer.remaining)
    buffer.get(data)
    listener.onGoAwayFrame(lastStream, code, data)
  }

  //////////// WINDOW_UPDATE ///////////////
  private def decodeWindowUpdateFrame(buffer: ByteBuffer, streamId: Int): Http2Result = {
    if (buffer.remaining != 4) {
      return Error(FRAME_SIZE_ERROR.goaway(s"WindowUpdate with invalid size: ${buffer.remaining()}"))
    }

    val size = buffer.getInt() & Masks.INT31
    if (size == 0) return Error {  // never less than 0 due to the mask above
      if (streamId == 0)
        PROTOCOL_ERROR.goaway(s"WINDOW_UPDATE with invalid update size 0")
      else
        PROTOCOL_ERROR.rst(streamId, s"WINDOW_UPDATE with invalid update size 0")
    }

    listener.onWindowUpdateFrame(streamId, size)
  }

  //////////// CONTINUATION ///////////////
  private def decodeContinuationFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {
    if (streamId == 0) {
      val msg = s"CONTINUATION frame with invalid stream dependency: 0x" +
        Integer.toHexString(streamId)
      return Error(PROTOCOL_ERROR.goaway(msg))
    } else if (!listener.inHeaderSequence) {
      return Error(PROTOCOL_ERROR.goaway(s"Received CONTINUATION frame outside of a HEADERS sequence"))
    }

    listener.onContinuationFrame(streamId, Flags.END_HEADERS(flags), buffer.slice())
  }
}

private object Http2FrameDecoder {

  /** Get the length field of the frame, consuming the bytes from the buffer.
    *
    * @return -1 if the buffer doesn't have 3 bytes for the length field,
    *         and the length field otherwise.
    */
  def getLengthField(buffer: ByteBuffer): Int = {
    ((buffer.get() & 0xff) << 16) |
    ((buffer.get() & 0xff) <<  8) |
    ((buffer.get() & 0xff) <<  0)
  }

  /** Get the stream id from the `Buffer` consuming exactly 4 bytes. */
  def getStreamId(buffer: ByteBuffer): Int =
    buffer.getInt() & Masks.STREAMID

  /** Read the padding length and strip the requisite bytes from the end of
    * the buffer using the `ByteBuffer.limit` method.
    */
  private def limitPadding(padding: Int, buffer: ByteBuffer): MaybeError = {
    if (padding == 0) Continue
    else if (buffer.remaining < padding) {
      // If the length of the padding is the length of the
      // frame payload or greater, the recipient MUST treat this as a
      // connection error (Section 5.4.1) of type PROTOCOL_ERROR.
      Error(PROTOCOL_ERROR.goaway(s"Padding ($padding) exceeds payload length: ${buffer.remaining}"))
    } else {
      buffer.limit(buffer.limit - padding)
      Continue
    }
  }
}
