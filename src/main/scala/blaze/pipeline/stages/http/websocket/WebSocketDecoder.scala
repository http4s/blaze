package blaze.pipeline.stages.http.websocket

import blaze.pipeline.stages.ByteToObjectStage
import java.nio.ByteBuffer

import WebsocketBits._
import scala.util.Random
import blaze.pipeline.stages.http.websocket.WebSocketDecoder.WebSocketFrame
import blaze.pipeline.stages.http.websocket.FrameTranscoder.TranscodeError

/**
 * @author Bryce Anderson
 *         Created on 1/15/14
 */
class WebSocketDecoder(isClient: Boolean, val maxBufferSize: Int = 0)
      extends FrameTranscoder(isClient) with ByteToObjectStage[WebSocketFrame] {

  val name = "Websocket Decoder"

  /** Encode objects to buffers
    * @param in object to decode
    * @return sequence of ByteBuffers to pass to the head
    */
  @throws[TranscodeError]
  def messageToBuffer(in: WebSocketFrame): Seq[ByteBuffer] = {

    val buff = _messageToBuffer(in)
    buff::Nil
  }

  /** Method that decodes ByteBuffers to objects. None reflects not enough data to decode a message
    * Any unused data in the ByteBuffer will be recycled and available for the next read
    * @param in ByteBuffer of immediately available data
    * @return optional message if enough data was available
    */
  @throws[TranscodeError]
  def bufferToMessage(in: ByteBuffer): Option[WebSocketFrame] = Option(_bufferToMessage(in))
}

object WebSocketDecoder {
  case class WebSocketFrame(opcode: Int, data: Array[Byte], finished: Boolean) {
    def length = data.length

    override def equals(obj: Any): Boolean = obj match {
      case WebSocketFrame(opcode, data, finished) =>
        this.opcode == opcode &&
        this.finished == finished &&
        this.data.length == data.length && {
          var i = 0
          var same = true
          while (i < data.length && same) {
            if (data(i) != this.data(i)) same = false
            i += 1
          }
          same
        }

      case _ => false
    }

    override def toString: String = {
      "WebSocketFrame(" +
        Integer.toHexString(opcode) + ", Bytes(" +
        data.length + "), finished: " + finished + ")"
    }
  }
}

