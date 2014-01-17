package blaze.pipeline.stages.http.websocket

import org.scalatest.{Matchers, WordSpec}
import java.nio.ByteBuffer
import blaze.pipeline.{TailStage, PipelineBuilder}
import blaze.pipeline.stages.HoldingHead

import scala.concurrent.Await
import scala.concurrent.duration._

import java.nio.charset.StandardCharsets.UTF_8
import blaze.pipeline.stages.http.websocket.WebSocketDecoder.WebSocketFrame
import scala.util.Random

/**
 * @author Bryce Anderson
 *         Created on 1/16/14
 */
class WebsocketSpec extends WordSpec with Matchers {

  def helloTxtMasked = Array(0x81, 0x85, 0x37, 0xfa,
                             0x21, 0x3d, 0x7f, 0x9f,
                             0x4d, 0x51, 0x58).map(_.toByte)

  def helloTxt = Array(0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f).map(_.toByte)

  class Reporter extends TailStage[WebSocketFrame] {
    def name: String = "Fetcher"
    def getMessage(): WebSocketFrame = {
      Await.result(channelRead(), 4.seconds)
    }
  }


  def pipeline(msg: Array[Byte], isClient: Boolean): Reporter = {
    val buff = ByteBuffer.wrap(msg)

    val tail = new Reporter
    PipelineBuilder(new HoldingHead(buff))
      .append(new WebSocketDecoder(isClient))
      .cap(tail)

    tail
  }

  def decode(msg: Array[Byte], isClient: Boolean): WebSocketFrame = {
    val buff = ByteBuffer.wrap(msg)

    new WebSocketDecoder(isClient).bufferToMessage(buff).get
  }

  def encode(msg: WebSocketFrame, isClient: Boolean): Array[Byte] = {
    new WebSocketDecoder(isClient).messageToBuffer(msg).head.array()
  }

  "Websocket decoder" should {

    "equate frames correctly" in {
      val f1 = WebSocketFrame(WebsocketBits.BINARY, Array(0x2.toByte, 0x3.toByte), true)
      val f11 = WebSocketFrame(WebsocketBits.BINARY, Array(0x2.toByte, 0x3.toByte), true)
      val f2 = WebSocketFrame(WebsocketBits.BINARY, Array(0x2.toByte, 0x3.toByte), false)
      val f3 = WebSocketFrame(WebsocketBits.TEXT, Array(0x2.toByte, 0x3.toByte), true)
      val f4 = WebSocketFrame(WebsocketBits.BINARY, Array(0x2.toByte, 0x4.toByte), true)

      f1 should equal(f1)
      f1 should equal(f11)
      f1 should not equal(f2)
      f1 should not equal(f3)
      f1 should not equal(f4)
    }

    "decode a hello world message" in {
//      val p = pipeline(helloTxtMasked, false)
//      p.getMessage() should equal (TextMessage("Hello".getBytes(UTF_8), true))
//
//      val p2 = pipeline(helloTxt, true)
//      p2.getMessage() should equal (TextMessage("Hello".getBytes(UTF_8), true))

      val result = decode(helloTxtMasked, false)
      result.finished should equal (true)
      new String(result.data, UTF_8) should equal ("Hello")

      val result2 = decode(helloTxt, true)
      result2.finished should equal (true)
      new String(result2.data, UTF_8) should equal ("Hello")

    }

    "encode a hello world message" in {
      val frame = WebSocketFrame(WebsocketBits.TEXT, "Hello".getBytes(UTF_8), false)
      val msg = decode(encode(frame, true), false)
      msg should equal (frame)
      msg.finished should equal (false)
      new String(msg.data, UTF_8) should equal ("Hello")
    }

    "encode and decode a message with 125 < len <= 0xffff" in {
      val bytes = 0 until 0xfff map(_.toByte) toArray
      val frame = WebSocketFrame(WebsocketBits.BINARY, bytes, false)

      val msg = decode(encode(frame, true), false)
      val msg2 = decode(encode(frame, false), true)

      msg should equal(frame)
      msg should equal(msg2)

    }

    "encode and decode a message len > 0xffff" in {
      val bytes = 0 until (0xffff + 1) map(_.toByte) toArray
      val frame = WebSocketFrame(WebsocketBits.BINARY, bytes, false)

      val msg = decode(encode(frame, true), false)
      val msg2 = decode(encode(frame, false), true)

      msg should equal(frame)
      msg should equal(msg2)
    }
  }

}
