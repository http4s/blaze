package org.http4s.blaze.http.websocket

import org.http4s.blaze.pipeline.stages.ByteToObjectStage
import java.nio.ByteBuffer

import org.http4s.websocket.FrameTranscoder
import org.http4s.websocket.WebsocketBits.WebSocketFrame
import org.http4s.websocket.FrameTranscoder.TranscodeError


class WebSocketDecoder(isClient: Boolean, val maxBufferSize: Int = 0)
      extends FrameTranscoder(isClient) with ByteToObjectStage[WebSocketFrame] {

  val name = "Websocket Decoder"

  /** Encode objects to buffers
    * @param in object to decode
    * @return sequence of ByteBuffers to pass to the head
    */
  @throws[TranscodeError]
  def messageToBuffer(in: WebSocketFrame): Seq[ByteBuffer] = frameToBuffer(in)

  /** Method that decodes ByteBuffers to objects. None reflects not enough data to decode a message
    * Any unused data in the ByteBuffer will be recycled and available for the next read
    * @param in ByteBuffer of immediately available data
    * @return optional message if enough data was available
    */
  @throws[TranscodeError]
  def bufferToMessage(in: ByteBuffer): Option[WebSocketFrame] = Option(bufferToFrame(in))
}
