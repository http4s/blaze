package org.http4s.blaze.http.http2

import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.http.http2.mocks.MockFrameListener

private object ProtocolFrameDecoder {

  private[this] class Listener(inHeaderSequence: Boolean) extends MockFrameListener(inHeaderSequence) {
    var frame: ProtocolFrame = ProtocolFrame.Empty

    override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: Array[Byte]): Result = {
      val ex = new Http2SessionException(errorCode, new String(debugData, StandardCharsets.UTF_8))
      frame = ProtocolFrame.GoAway(lastStream, ex)
      Continue
    }

    override def onPingFrame(ack: Boolean, data: Array[Byte]): Result = {
      frame = ProtocolFrame.Ping(ack, data)
      Continue
    }

    override def onSettingsFrame(settings: Option[Seq[Setting]]): Result = {
      frame = ProtocolFrame.Settings(settings)
      Continue
    }
  }

  def decode(
    buffer: ByteBuffer,
    inHeadersSequence: Boolean = false,
    maxFrameSize: Int = Http2Settings.default.maxFrameSize
  ): ProtocolFrame = {
    val listener = new Listener(inHeadersSequence)
    val decoder = new FrameDecoder(toSettings(maxFrameSize), listener)

    decoder.decodeBuffer(buffer) match {
      case Continue => listener.frame
      case BufferUnderflow => ProtocolFrame.Empty
      case Error(ex) => throw ex
    }
  }

  private[this] def toSettings(maxFrameSize: Int): Http2Settings = {
    if (maxFrameSize == Http2Settings.default.maxFrameSize) Http2Settings.default
    else Http2Settings.default.copy(maxFrameSize = maxFrameSize)
  }
}
