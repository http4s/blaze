package org.http4s.blaze.http.websocket

import scala.language.postfixOps

import org.scalatest.{Matchers, WordSpec}
import java.nio.ByteBuffer

import java.nio.charset.StandardCharsets.UTF_8
import org.http4s.blaze.http.websocket.WebSocketDecoder._


/**
 * @author Bryce Anderson
 *         Created on 1/16/14
 */
class WebsocketSpec extends WordSpec with Matchers {

  def helloTxtMasked = Array(0x81, 0x85, 0x37, 0xfa,
                             0x21, 0x3d, 0x7f, 0x9f,
                             0x4d, 0x51, 0x58).map(_.toByte)

  def helloTxt = Array(0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f).map(_.toByte)

  def decode(msg: Array[Byte], isClient: Boolean): WebSocketFrame = {
    val buff = ByteBuffer.wrap(msg)

    new WebSocketDecoder(isClient).bufferToMessage(buff).get
  }

  def encode(msg: WebSocketFrame, isClient: Boolean): Array[Byte] = {
    val msgs = new WebSocketDecoder(isClient).messageToBuffer(msg)
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

      f1 should equal(f1)
      f1 should equal(f11)
      f1 should not equal(f2)
      f1 should not equal(f3)
      f1 should not equal(f4)
    }

    "decode a hello world message" in {

      val result = decode(helloTxtMasked, false)
      result.last should equal (true)
      new String(result.data, UTF_8) should equal ("Hello")

      val result2 = decode(helloTxt, true)
      result2.last should equal (true)
      new String(result2.data, UTF_8) should equal ("Hello")
    }

    "encode a hello world message" in {
      val frame = Text("Hello".getBytes(UTF_8), false)
      val msg = decode(encode(frame, true), false)
      msg should equal (frame)
      msg.last should equal (false)
      new String(msg.data, UTF_8) should equal ("Hello")
    }

    "encode and decode a message with 125 < len <= 0xffff" in {
      val bytes = 0 until 0xfff map(_.toByte) toArray
      val frame = Binary(bytes, false)

      val msg = decode(encode(frame, true), false)
      val msg2 = decode(encode(frame, false), true)

      msg should equal(frame)
      msg should equal(msg2)
    }

    "encode and decode a message len > 0xffff" in {
      val bytes = 0 until (0xffff + 1) map(_.toByte) toArray
      val frame = Binary(bytes, false)

      val msg = decode(encode(frame, true), false)
      val msg2 = decode(encode(frame, false), true)

      msg should equal(frame)
      msg should equal(msg2)
    }
  }

}
