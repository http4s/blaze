package org.http4s.blaze.pipeline.stages.http.websocket

import org.http4s.blaze.pipeline.stages.ByteToObjectStage
import java.nio.ByteBuffer

import java.nio.charset.StandardCharsets.UTF_8

import org.http4s.blaze.pipeline.stages.http.websocket.WebSocketDecoder.WebSocketFrame
import org.http4s.blaze.pipeline.stages.http.websocket.FrameTranscoder.TranscodeError
import java.net.ProtocolException

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
  def messageToBuffer(in: WebSocketFrame): Seq[ByteBuffer] = _messageToBuffer(in)

  /** Method that decodes ByteBuffers to objects. None reflects not enough data to decode a message
    * Any unused data in the ByteBuffer will be recycled and available for the next read
    * @param in ByteBuffer of immediately available data
    * @return optional message if enough data was available
    */
  @throws[TranscodeError]
  def bufferToMessage(in: ByteBuffer): Option[WebSocketFrame] = Option(_bufferToMessage(in))
}

object WebSocketDecoder {
  sealed trait WebSocketFrame {

    def opcode: Int
    def data: Array[Byte]
    def last: Boolean
    
    def length = data.length

    override def equals(obj: Any): Boolean = obj match {
      case wf: WebSocketFrame =>
        this.opcode == wf.opcode &&
        this.last == wf.last &&
        this.data.length == wf.data.length && {
          var i = 0
          var same = true
          while (i < this.data.length && same) {
            if (wf.data(i) != this.data(i)) same = false
            i += 1
          }
          same
        }

      case _ => false
    }
  }

  sealed trait ControlFrame extends WebSocketFrame {
    final def last: Boolean = true
  }

  sealed trait Text extends WebSocketFrame {
    def str: String
    def opcode = WebsocketBits.TEXT

    override def toString: String = s"Text('$str', last: $last)"
  }

  private class BinaryText(val data: Array[Byte], val last: Boolean) extends Text {
    lazy val str: String = new String(data, UTF_8)
  }

  private class StringText(override val str: String, val last: Boolean) extends Text {
    lazy val data: Array[Byte] = str.getBytes(UTF_8)
  }

  object Text {
    def apply(str: String, last: Boolean = true): Text = new StringText(str, last)
    def apply(data: Array[Byte], last: Boolean): Text = new BinaryText(data, last)
    def apply(data: Array[Byte]): Text = new BinaryText(data, true)
    def unapply(txt: Text): Option[(String, Boolean)] = Some((txt.str, txt.last))
  }

  final case class Binary(data: Array[Byte], last: Boolean = true) extends WebSocketFrame {
    def opcode = WebsocketBits.BINARY
    override def toString: String = s"Binary(Array(${data.length}), last: $last)"
  }

  final case class Continuation(data: Array[Byte], last: Boolean) extends WebSocketFrame {
    def opcode: Int = WebsocketBits.CONTINUATION
    override def toString: String = s"Continuation(Array(${data.length}), last: $last)"
  }

  final case class Ping(data: Array[Byte] = Array.empty) extends ControlFrame {
    def opcode = WebsocketBits.PING
    override def toString: String = {
      if (data.length > 0) s"Ping(Array(${data.length}))"
      else s"Ping"
    }
  }

  final case class Pong(data: Array[Byte] = Array.empty) extends ControlFrame {
    def opcode = WebsocketBits.PONG
    override def toString: String = {
      if (data.length > 0) s"Pong(Array(${data.length}))"
      else s"Pong"
    }
  }

  final case class Close(data: Array[Byte] = Array.empty) extends ControlFrame {
    def opcode = WebsocketBits.CLOSE

    def closeCode: Int = if (data.length > 0) {
      (data(0) << 8 & 0xff00) | (data(1) & 0xff)      // 16-bit unsigned
    } else 1005 // No code present

    override def toString: String = {
      if (data.length > 0) s"Close(Array(${data.length}))"
      else s"Close"
    }
  }



  @throws[ProtocolException]
  def makeFrame(opcode: Int, data: Array[Byte], last: Boolean): WebSocketFrame = opcode match {
    case WebsocketBits.TEXT => new BinaryText(data, last)

    case WebsocketBits.BINARY => Binary(data, last)

    case WebsocketBits.PING =>
      if (!last) throw new ProtocolException("Control frame cannot be fragmented: Ping")
      else Ping(data)

    case WebsocketBits.PONG =>
      if (!last) throw new ProtocolException("Control frame cannot be fragmented: Pong")
      else Pong(data)

    case WebsocketBits.CLOSE =>
      if (data.length == 1) throw new ProtocolException("Close frame must have 0 data bits or at least 2")
      if (!last) throw new ProtocolException("Control frame cannot be fragmented: Close")
      Close(data)
  }


}

