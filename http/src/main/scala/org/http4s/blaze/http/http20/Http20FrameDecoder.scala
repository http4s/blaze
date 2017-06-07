package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import Http2Settings.Setting
import Http2Exception._

import scala.collection.mutable.ArrayBuffer


/* The job of the Http20FrameCodec is to slice the ByteBuffers. It does
   not attempt to decode headers or perform any size limiting operations */
private trait Http20FrameDecoder {
  import bits._

  protected val handler: FrameHandler
  protected val http2Settings: Http2Settings

  /** Decode a data frame. */
  def decodeBuffer(buffer: ByteBuffer): Http2Result = {
    if (buffer.remaining() < HeaderSize) {
      return BufferUnderflow
    }

    buffer.mark()
    val len: Int = ((buffer.get() & 0xff) << 16) |
                   ((buffer.get() & 0xff) << 8 ) |
                    (buffer.get() & 0xff)

    val frameType = buffer.get()
    // we have a full frame, get the header information
    val flags = buffer.get()
    val streamId = buffer.getInt() & Masks.STREAMID
    // this concludes the 9 byte header. `in` is now to the payload

    if (len > http2Settings.maxFrameSize) {
      Error(FRAME_SIZE_ERROR(s"HTTP2 packet is to large to handle.", streamId, fatal = true))
    } else if (handler.inHeaderSequence() && frameType != FrameTypes.CONTINUATION) {
    // We are in the middle of some header frames which is a no-go
      protoError(s"Received frame type $frameType while in in headers sequence")
    } else if (len > buffer.remaining()) {   // We still don't have a full frame
      buffer.reset()
      BufferUnderflow
    } else { // full frame. Decode.
      // set frame sizes in the ByteBuffer and decode
      val oldLimit = buffer.limit()
      val endOfFrame = buffer.position() + len
      buffer.limit(endOfFrame)

      val r = frameType match {
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
        case code                     => onExtensionFrame(code.toInt, streamId, flags, buffer.slice())
      }

      // reset buffer limits
      buffer.limit(oldLimit)
      buffer.position(endOfFrame)

      r
    }
  }
  
  /** Overriding this method allows for easily supporting extension frames */
  def onExtensionFrame(code: Int, streamId: Int, flags: Byte, buffer: ByteBuffer): Http2Result =
    handler.onExtensionFrame(code, streamId, flags, buffer)

  //////////////// Decoding algorithms ///////////////////////////////////////////////////////////

  //////////// DATA ///////////////
  private def decodeDataFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {

    if (streamId == 0) {
      return protoError("Data frame with streamID 0x0")
    }

    val payload = buffer.remaining()

    if (Flags.PADDED(flags)) {
      val r = limitPadding(buffer)
      if (!r.success) return r
    }

    handler.onDataFrame(streamId, Flags.END_STREAM(flags), buffer.slice(), payload)
  }

  //////////// HEADERS ///////////////
  private def decodeHeaderFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {
    if (streamId == 0) {
      return protoError("Headers frame with streamID 0x0")
    }

    if (Flags.PADDED(flags)) {
      val r = limitPadding(buffer)
      if (!r.success) return r
    }

    val priority =  if (Flags.PRIORITY(flags)) Some(getPriority(buffer))
                    else None

    if (priority.isDefined && priority.get.dependentStreamId == streamId)
      protoError(s"Header stream depends on itself", streamId)
    else handler.onHeadersFrame(streamId, priority, Flags.END_HEADERS(flags), Flags.END_STREAM(flags), buffer.slice())
  }


  //////////// PRIORITY ///////////////
  private def decodePriorityFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {

    if (streamId == 0) {
      return protoError("Priority frame with streamID 0x0")
    }

    if (buffer.remaining() != 5) {    // Make sure the frame has the right amount of data
      val msg = "Invalid PRIORITY frame size, required 5, received" + buffer.remaining()
      return Error(FRAME_SIZE_ERROR(msg, streamId, fatal = false))
    }

    val priority = getPriority(buffer)

    if (priority.dependentStreamId == 0) protoError("Priority frame with stream dependency 0x0")
    else if (priority.dependentStreamId == streamId) protoError("Priority frame depends on itself", streamId)
    else handler.onPriorityFrame(streamId, priority)
  }

  private def getPriority(buffer: ByteBuffer): Priority = {
    val rawInt = buffer.getInt()
    val priority = (buffer.get() & 0xff) + 1
    val ex = Flags.DepExclusive(rawInt)
    Priority(Flags.DepID(rawInt), ex, priority)
  }

  //////////// RST_STREAM ///////////////
  private def decodeRstStreamFrame(buffer: ByteBuffer, streamId: Int): Http2Result = {
    if (buffer.remaining() != 4) {
      val msg = "Invalid RST_STREAM frame size, required 4, received " + buffer.remaining()
      return Error(FRAME_SIZE_ERROR(msg, streamId, fatal = true))
    }

    if (streamId == 0) {
      return protoError("RST_STREAM frame with stream ID 0")
    }

    val code = buffer.getInt()

    handler.onRstStreamFrame(streamId, code)
  }

  //////////// SETTINGS ///////////////
  private def decodeSettingsFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {
    val len = buffer.remaining()
    val isAck = Flags.ACK(flags)

    val settingsCount = len / 6 // 6 bytes per setting
    if (len % 6 != 0) { // Invalid frame size
      val msg = s"SETTINGS frame payload must be multiple of 6 bytes, size: $len"
      return Error(FRAME_SIZE_ERROR(msg, streamId, fatal = true))
    }

    if (isAck && settingsCount != 0) {
      val msg = "SETTINGS ACK frame with settings payload"
      return Error(FRAME_SIZE_ERROR(msg, streamId, fatal = true))
    }

    if (streamId != 0x0) {
      return protoError(s"SETTINGS frame with invalid stream id: $streamId")
    }

    val settings = new ArrayBuffer[Setting](settingsCount)

    def go(remaining: Int): Unit = if (remaining > 0) {
      val id: Int = buffer.getShort() & 0xffff
      val value: Long = buffer.getInt() & 0xffffffffl
      settings += Setting(id, value)
      go(remaining - 1)
    }
    go(settingsCount)

    handler.onSettingsFrame(isAck, settings)
  }

  //////////// PUSH_PROMISE ///////////////
  private def decodePushPromiseFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {
    if (streamId == 0) {
      return protoError("Data frame with streamID 0x0")
    }

    if (Flags.PADDED(flags)) {
      val r = limitPadding(buffer)
      if (!r.success) return r
    }

    val promisedId = buffer.getInt() & Masks.int31

    handler.onPushPromiseFrame(streamId, promisedId, Flags.END_HEADERS(flags), buffer.slice())
  }

  //////////// PING ///////////////
  private def decodePingFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {
    val pingSize = 8

    if (streamId != 0) {
      return protoError("PING frame with streamID != 0x0")
    }

    if (buffer.remaining() != pingSize) {
      val msg = "Invalid PING frame size. Expected 4, received " + buffer.remaining()
      return Error(FRAME_SIZE_ERROR(msg, fatal = true))
    }

    val pingBytes = new Array[Byte](pingSize)
    buffer.get(pingBytes)

    handler.onPingFrame(Flags.ACK(flags), pingBytes)
  }

  //////////// GOAWAY ///////////////
  private def decodeGoAwayFrame(buffer: ByteBuffer, streamId: Int): Http2Result = {

    if (buffer.remaining() < 8) {
      val msg = "GOAWAY frame is wrong size. Expected  > 8, received " + buffer.remaining()
      return Error(FRAME_SIZE_ERROR(msg, fatal = true))
    }

    if (streamId != 0) {
      return protoError("GOAWAY frame with streamID != 0x0")
    }

    val lastStream = Flags.DepID(buffer.getInt)
    val code: Long = buffer.getInt() & 0xffffffffl   // java doesn't have unsigned integers

    handler.onGoAwayFrame(lastStream, code, buffer.slice())
  }

  //////////// WINDOW_UPDATE ///////////////
  private def decodeWindowUpdateFrame(buffer: ByteBuffer, streamId: Int): Http2Result = {
    if (buffer.remaining() != 4) {
      val msg = "WINDOW_UPDATE frame frame is wrong size. Expected 8, received " + buffer.remaining()
      return Error(FRAME_SIZE_ERROR(msg, streamId, fatal = true))
    }

    val size = buffer.getInt() & Masks.int31

    handler.onWindowUpdateFrame(streamId, size)
  }

  //////////// CONTINUATION ///////////////
  private def decodeContinuationFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {
    if (streamId <= 0) {
      val msg = s"CONTINUATION frame with invalid stream dependency: 0x${Integer.toHexString(streamId)}"
      protoError(msg)
    }
    else handler.onContinuationFrame(streamId, Flags.END_HEADERS(flags), buffer.slice())
  }

  private def protoError(msg: String): Error =
    Error(PROTOCOL_ERROR(msg, fatal = true))

  private def protoError(msg: String, stream: Int): Error =
    Error(PROTOCOL_ERROR(msg, stream, fatal = true))


  @inline
  private def limitPadding(buffer: ByteBuffer): MaybeError = {
    val padding = buffer.get() & 0xff
    if (padding > 0) {
      if (padding >= buffer.remaining()) {
        protoError(s"Padding, $padding, exceeds payload length: ${buffer.remaining}")
      }
      else {
        buffer.limit(buffer.limit() - padding)
        Continue
      }
    }
    else Continue
  }
}
