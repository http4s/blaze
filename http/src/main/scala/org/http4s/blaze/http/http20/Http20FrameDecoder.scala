package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import Settings.Setting
import Http2Exception._

import scala.collection.mutable.ArrayBuffer


/* The job of the Http20FrameCodec is to slice the ByteBuffers. It does
   not attempt to decode headers or perform any size limiting operations */
trait Http20FrameDecoder {
  import bits._

  def handler: FrameHandler

  /** Decode a data frame. false signals "not enough data" */

  def decodeBuffer(buffer: ByteBuffer): Http2Result = {
    if (buffer.remaining() < HeaderSize) {
      return BufferUnderflow
    }

    buffer.mark()
    val len: Int = ((buffer.get() & 0xff) << 16) |
                   ((buffer.get() & 0xff) << 8 ) |
                    (buffer.get() & 0xff)

    if (len + 6 > buffer.remaining()) {   // We still don't have a full frame
      buffer.reset()
      return BufferUnderflow
    }

    // we have a full frame, get the header information
    val frameType = buffer.get()
    val flags = buffer.get()
    val streamId = buffer.getInt() & Masks.STREAMID // this concludes the 9 byte header. `in` is now to the payload
    
    // Make sure we are not in the middle of some header frames
    if (handler.inHeaderSequence() && frameType != FrameTypes.CONTINUATION) {
      return Error(PROTOCOL_ERROR(s"Received frame type $frameType while in in headers sequence"))
    }

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
      case code                     => onExtensionFrame(code, streamId, flags, buffer.slice())
    }

    // reset buffer limits
    buffer.limit(oldLimit)
    buffer.position(endOfFrame)

    return r
  }

  /** true if the decoder is decoding CONTINUATION frames */
  final def inHeaderSequence(): Boolean = handler.inHeaderSequence()
  
  /** Overriding this method allows for easily supporting extension frames */
  def onExtensionFrame(code: Int, streamId: Int, flags: Byte, buffer: ByteBuffer): Http2Result =
    handler.onExtensionFrame(code, streamId, flags, buffer)

  //////////////// Decoding algorithms ///////////////////////////////////////////////////////////

  //////////// DATA ///////////////
  private def decodeDataFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {

    if (streamId == 0) {
      return Error(PROTOCOL_ERROR("Data frame with streamID 0x0"))
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
      return Error(PROTOCOL_ERROR("Headers frame with streamID 0x0"))
    }

    if (Flags.PADDED(flags)) {
      val r = limitPadding(buffer)
      if (!r.success) return r
    }

    val priority =  if (Flags.PRIORITY(flags)) Some(getPriority(buffer))
                    else None

    handler.onHeadersFrame(streamId, priority, Flags.END_HEADERS(flags), Flags.END_STREAM(flags), buffer.slice())
  }



  //////////// PRIORITY ///////////////
  private def decodePriorityFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {

    if (streamId == 0) {
      return Error(PROTOCOL_ERROR("Priority frame with streamID 0x0"))
    }

    if (buffer.remaining() != 5) {    // Make sure the frame has the right amount of data
      return Error(FRAME_SIZE_ERROR("Invalid PRIORITY frame size, required 5, received" + buffer.remaining(), streamId))
    }

    val priority = getPriority(buffer)

    if (priority.dependentStreamId == 0) Error(PROTOCOL_ERROR("Priority frame with stream dependency 0x0"))
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
      return Error(FRAME_SIZE_ERROR("Invalid RST_STREAM frame size, required 4, received " + buffer.remaining(), streamId))
    }

    if (streamId == 0) {
      return Error(PROTOCOL_ERROR("RST_STREAM frame with stream ID 0"))
    }

    val code = buffer.getInt()

    handler.onRstStreamFrame(streamId, code)
  }

  //////////// SETTINGS ///////////////
  private def decodeSettingsFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {
    val len = buffer.remaining()
    val settingsCount = len / 6 // 6 bytes per setting

    val isAck = Flags.ACK(flags)

    if (len % 6 != 0) { // Invalid frame size
      return Error(FRAME_SIZE_ERROR(s"SETTINGS frame payload must be multiple of 6 bytes, size: $len", streamId))
    }

    if (isAck && settingsCount != 0) {
      return Error(FRAME_SIZE_ERROR("SETTINGS ACK frame with settings payload", streamId))
    }

    if (streamId != 0x0) {
      return Error(PROTOCOL_ERROR(s"SETTINGS frame with invalid stream id", streamId))
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
      return Error(PROTOCOL_ERROR("Data frame with streamID 0x0"))
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
      return Error(PROTOCOL_ERROR("PING frame with streamID != 0x0"))
    }

    if (buffer.remaining() != pingSize) {
      return Error(FRAME_SIZE_ERROR("Invalid PING frame size. Expected 4, received " + buffer.remaining()))
    }

    val pingBytes = new Array[Byte](pingSize)
    buffer.get(pingBytes)

    handler.onPingFrame(Flags.ACK(flags), pingBytes)
  }

  //////////// GOAWAY ///////////////
  private def decodeGoAwayFrame(buffer: ByteBuffer, streamId: Int): Http2Result = {

    if (buffer.remaining() < 8) {
      return Error(FRAME_SIZE_ERROR("GOAWAY frame is wrong size. Expected 8, received " + buffer.remaining()))
    }

    if (streamId != 0) {
      return Error(PROTOCOL_ERROR("GOAWAY frame with streamID != 0x0"))
    }

    val lastStream = Flags.DepID(buffer.getInt)
    val code: Long = buffer.getInt() & 0xffffffffl   // java doesn't have unsigned integers

    handler.onGoAwayFrame(lastStream, code, buffer.slice())
  }

  //////////// WINDOW_UPDATE ///////////////
  private def decodeWindowUpdateFrame(buffer: ByteBuffer, streamId: Int): Http2Result = {
    if (buffer.remaining() != 4) {
      return Error(FRAME_SIZE_ERROR("WINDOW_UPDATE frame frame is wrong size. Expected 8, received " +
                        buffer.remaining(), streamId))
    }

    val size = buffer.getInt() & Masks.int31

    if (size == 0) {
      return Error(PROTOCOL_ERROR("Invalid WINDOW_UPDATE size of 0x0"))
    }

    handler.onWindowUpdateFrame(streamId, size)
  }

  //////////// CONTINUATION ///////////////
  private def decodeContinuationFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Http2Result = {
    if (streamId <= 0) {
      val msg = s"CONTINUATION frame with invalid stream dependency: 0x${Integer.toHexString(streamId)}"
      Error(PROTOCOL_ERROR(msg))
    }
    else handler.onContinuationFrame(streamId, Flags.END_HEADERS(flags), buffer.slice())
  }


  @inline
  private def limitPadding(buffer: ByteBuffer): MaybeError = {
    val padding = buffer.get() & 0xff
    if (padding > 0) {
      if (padding >= buffer.remaining()) {
        Error(PROTOCOL_ERROR(s"Padding, $padding, exceeds payload length: ${buffer.remaining}"))
      }
      else {
        buffer.limit(buffer.limit() - padding)
        Continue
      }
    }
    else Continue
  }
}
