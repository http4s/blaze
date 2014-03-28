package org.http4s.blaze.pipeline.stages.spdy

import org.scalatest.{Matchers, WordSpec}
import java.nio.ByteBuffer

/**
 * @author Bryce Anderson
 *         Created on 1/26/14
 */
class SpdyFrameCodecSpec extends WordSpec with Matchers {

  val data = ByteBuffer.wrap("Hello".getBytes("UTF-8")).asReadOnlyBuffer()

  def dataFrame = {
    val data = ByteBuffer.wrap("Hello".getBytes("UTF-8")).asReadOnlyBuffer()
    DataFrame(data, 1, true)
  }

  def synStreamFrame = {
    val headers = Map("foo" -> Seq("bar"), "baz" -> Seq(), "ping" -> Seq("pong1", "pong2"))
    SynStreamFrame(1, headers, true)
  }

  def synReplyFrame = {
    val headers = Map("foo" -> Seq("bar"), "baz" -> Seq(), "ping" -> Seq("pong1", "pong2"))
    SynReplyFrame(1, headers, false)
  }

  def rstFrame = {
    RstStreamFrame(1, RstCode.FLOW_CONTROL_ERROR)
  }

  def pingFrame = PingFrame(32)

  def goAwayFrame = GoAwayFrame(4, GoAwayCode.PROTOCOL_ERROR)

  def headersFrame = {
    val headers = Map("foo" -> Seq("bar"), "baz" -> Seq(), "ping" -> Seq("pong1", "pong2"))
    HeadersFrame(1, headers, false)
  }

  def settingsFrame = {
    val settings = Seq(
      Setting(1, SettingID.SETTINGS_CURRENT_CWND, 5),
      Setting(1, SettingID.SETTINGS_MAX_CONCURRENT_STREAMS, 5)
    )
    SettingsFrame(settings, true)
  }
  
  def windowUpdateFrame = WindowUpdateFrame(2, 4)
  
  def decode(buff: ByteBuffer, requireFull: Boolean = true): SpdyFrame = {
    val h = new Spdy3_1FrameCodec().bufferToMessage(buff).get

    if (requireFull)
      assert(buff.position() == buff.limit())

    h
  }
  
  def concat(buffs: Seq[ByteBuffer]): ByteBuffer = {
    val len = buffs.foldLeft(0)((i, b) => i + b.remaining())
    val buff = ByteBuffer.allocate(len)
    buffs.foreach(buff.put)
    
    buff.flip()
    buff
  }

  def encode(frame: SpdyFrame): ByteBuffer = {
    val codec = new Spdy3_1FrameCodec()
    concat(codec.messageToBuffer(frame))
  }

  "Spdy Types" should {
    "encode DataFrames" in {
      val buffs = new Spdy3_1FrameCodec().messageToBuffer(dataFrame)
      buffs.head.getInt(4) & 0xffffff should equal(5)
      buffs.tail.head should equal (data)
    }

    "encode SynStreamFrames" in {
      val buffs = encode(synStreamFrame)
    }

    "encode SynReplyFrames" in {
      val buffs = encode(synReplyFrame)
    }

    "encode RstFrame" in {
      val buffs = encode(rstFrame)
    }

    "encode a SettingsFrame" in {
      val buffs = encode(settingsFrame)
    }
    
    
  }

  "SpdyFrameCodec" should {
    "Decode a Data frame" in {
      decode(encode(dataFrame)) should equal(dataFrame)
    }

    "Decode a SynFrame" in {
      decode(encode(synStreamFrame)) should equal(synStreamFrame)
    }

    "Decode a SynReplyFrame" in {
      decode(encode(synReplyFrame)) should equal(synReplyFrame)
    }

    "Decode a RstFrame" in {
      decode(encode(rstFrame)) should equal(rstFrame)
    }

    "Decode a SettingsFrame" in {
      decode(encode(settingsFrame)) should equal(settingsFrame)
    }

    "Decode a PingFrame" in {
      decode(encode(pingFrame)) should equal(pingFrame)
    }

    "Decode a GoAwayFrame" in {
      decode(encode(goAwayFrame)) should equal(goAwayFrame)
    }

    "Decode a HeadersFrame" in {
      decode(encode(headersFrame)) should equal(headersFrame)
    }
    
    "Decode a WindowUpdate frame" in {
      decode(encode(windowUpdateFrame)) should equal(windowUpdateFrame)
    }
  }

}
