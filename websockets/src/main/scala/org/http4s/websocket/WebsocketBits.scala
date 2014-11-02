package org.http4s.websocket

import java.net.ProtocolException
import java.nio.charset.StandardCharsets.UTF_8


object WebsocketBits {

  // Masks for extracting fields
  val OP_CODE = 0xf
  val FINISHED = 0x80
  val MASK = 0x80
  val LENGTH = 0x7f
  val RESERVED = 0xe

  // message op codes
  val CONTINUATION = 0x0
  val TEXT = 0x1
  val BINARY = 0x2
  val CLOSE = 0x8
  val PING = 0x9
  val PONG = 0xa

  // Type constructors
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


  // Websocket types

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


}
