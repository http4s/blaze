package org.http4s.blaze.http.websocket

import org.http4s.websocket.FrameTranscoder
import org.http4s.websocket.WebsocketBits._

import java.nio.ByteBuffer

import java.nio.charset.StandardCharsets.UTF_8

import org.specs2.mutable.Specification


class WebsocketSpec extends Specification {

  def helloTxtMasked = Array(0x81, 0x85, 0x37, 0xfa,
    0x21, 0x3d, 0x7f, 0x9f,
    0x4d, 0x51, 0x58).map(_.toByte)

  def helloTxt = Array(0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f).map(_.toByte)

  def decode(msg: Array[Byte], isClient: Boolean): WebSocketFrame =
    new FrameTranscoder(isClient).bufferToFrame(ByteBuffer.wrap(msg))


  def encode(msg: WebSocketFrame, isClient: Boolean): Array[Byte] = {
    val msgs = new FrameTranscoder(isClient).frameToBuffer(msg)
    val sz = msgs.foldLeft(0)((c, i) => c + i.remaining())
    val b = ByteBuffer.allocate(sz)
    msgs.foreach(b.put)
    b.array()
  }

  "Websocket decoder" should {

    "equate frames correctly" in {
      val f1 = Binary(Array(0x2.toByte, 0x3.toByte), true)
      val f11 = Binary(Array(0x2.toByte, 0x3.toByte), true)
      val f2 = Binary(Array(0x2.toByte, 0x3.toByte), false)
      val f3 = Text(Array(0x2.toByte, 0x3.toByte), true)
      val f4 = Binary(Array(0x2.toByte, 0x4.toByte), true)

      f1 should_== f1
      f1 should_== f11
      f1 should_!= f2
      f1 should_!= f3
      f1 should_!= f4
    }

    "decode a hello world message" in {

      val result = decode(helloTxtMasked, false)
      result.last should_== true
      new String(result.data, UTF_8) should_== "Hello"

      val result2 = decode(helloTxt, true)
      result2.last should_== true
      new String(result2.data, UTF_8) should_== "Hello"
    }

    "encode a hello world message" in {
      val frame = Text("Hello".getBytes(UTF_8), false)
      val msg = decode(encode(frame, true), false)
      msg should_== frame
      msg.last should_== false
      new String(msg.data, UTF_8) should_== "Hello"
    }

    "encode and decode a message with 125 < len <= 0xffff" in {
      val bytes = (0 until 0xfff).map(_.toByte).toArray
      val frame = Binary(bytes, false)

      val msg = decode(encode(frame, true), false)
      val msg2 = decode(encode(frame, false), true)

      msg should_== frame
      msg should_== msg2
    }

    "encode and decode a message len > 0xffff" in {
      val bytes = (0 until (0xffff + 1)).map(_.toByte).toArray
      val frame = Binary(bytes, false)

      val msg = decode(encode(frame, true), false)
      val msg2 = decode(encode(frame, false), true)

      msg should_== frame
      msg should_== msg2
    }
  }

}
