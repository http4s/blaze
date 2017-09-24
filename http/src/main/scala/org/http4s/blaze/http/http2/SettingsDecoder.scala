package org.http4s.blaze.http.http2
import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.http.http2.bits.{Flags, FrameTypes}

import scala.collection.mutable.ArrayBuffer

/** Utility for decoding a settings frame
  * @see https://tools.ietf.org/html/rfc7540#section-6.5
  */
private object SettingsDecoder {

  case class SettingsFrame(isAck: Boolean, settings: Seq[Setting])

  /** Decode a settings frame
    *
    * @param buffer `ByteBuffer` consisting of exactly the frame, including the header.
    * @return A [[SettingsFrame]] or a [[Http2Exception]]
    */
  def decodeSettingsFrame(buffer: ByteBuffer): Either[Http2Exception, SettingsFrame] = {
    val len = Http2FrameDecoder.getLengthField(buffer)
    assert(len + bits.HeaderSize - bits.LengthFieldSize == buffer.remaining())

    val tpe = buffer.get()

    if (tpe != FrameTypes.SETTINGS) Left(Http2Exception.PROTOCOL_ERROR.goaway("Expected SETTINGS frame"))
    else {
      val flags = buffer.get()
      val streamId = Http2FrameDecoder.getStreamId(buffer)
      decodeSettingsFrame(buffer, streamId, flags)
    }
  }

  /** Decode the body of a SETTINGS frame
    *
    * @param buffer `ByteBuffer` containing exactly the body of the settings frame
    * @param streamId stream id obtained from the frame header. Must be 0x0.
    * @param flags flags obtained from the frame header.
    */
  def decodeSettingsFrame(buffer: ByteBuffer, streamId: Int, flags: Byte): Either[Http2Exception, SettingsFrame] = {
    import Http2Exception._

    val len = buffer.remaining
    val isAck = Flags.ACK(flags)

    if (len % 6 != 0) { // Invalid frame size
    val msg = s"SETTINGS frame payload must be multiple of 6 bytes, size: $len"
      return Left(FRAME_SIZE_ERROR.goaway(msg))
    }

    val settingsCount = len / 6 // 6 bytes per setting

    if (isAck && settingsCount != 0) {
      val msg = s"SETTINGS ACK frame with settings payload ($settingsCount settings)"
      return Left(FRAME_SIZE_ERROR.goaway(msg))
    }

    if (streamId != 0x0) {
      return Left(PROTOCOL_ERROR.goaway(s"SETTINGS frame with invalid stream id: $streamId"))
    }

    val settings = Vector.newBuilder[Setting]

    while(buffer.hasRemaining) {
      val id: Int = buffer.getShort() & 0xffff
      val value = buffer.getInt()
      settings += Setting(id, value)
    }

    Right(SettingsFrame(isAck, settings.result))
  }

  /** Create a [[MutableHttp2Settings]] from a collection of settings */
  def settingsFromFrame(settings: Seq[Setting]): MutableHttp2Settings = {
    val next = MutableHttp2Settings.default()
    updateSettings(next, settings)
    next
  }

  private[this] def updateSettings(settings: MutableHttp2Settings, next: Seq[Setting]): Unit = {
    import Http2Settings._
    next.foreach {
      case HEADER_TABLE_SIZE(size)      => settings.headerTableSize = size.toInt
      case ENABLE_PUSH(enabled)         => settings.pushEnabled = enabled != 0
      case MAX_CONCURRENT_STREAMS(max)  => settings.maxConcurrentStreams = max.toInt
      case INITIAL_WINDOW_SIZE(size)    => settings.initialWindowSize = size.toInt
      case MAX_FRAME_SIZE(size)         => settings.maxFrameSize = size.toInt
      case MAX_HEADER_LIST_SIZE(size)   => settings.maxHeaderListSize = size.toInt
    }
  }
}
