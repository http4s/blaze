package org.http4s.blaze.http.spdy

import org.http4s.blaze.util.BufferTools
import org.specs2.mutable._
import java.nio.ByteBuffer

class SpdyFrameCodecSpec extends Specification {

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
    val buff = BufferTools.allocate(len)
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
      buffs.head.getInt(4) & 0xffffff should_== 5
      buffs.tail.head should_== data
    }

    "encode SynStreamFrames" in {
      val buffs = encode(synStreamFrame)
      buffs should_!= null
    }

    "encode SynReplyFrames" in {
      val buffs = encode(synReplyFrame)
      buffs should_!= null
    }

    "encode RstFrame" in {
      val buffs = encode(rstFrame)
      buffs should_!= null
    }

    "encode a SettingsFrame" in {
      val buffs = encode(settingsFrame)
      buffs should_!= null
    }
    
    
  }

  "SpdyFrameCodec" should {
    "Decode a Data frame" in {
      decode(encode(dataFrame)) should_== dataFrame
    }

    "Decode a SynFrame" in {
      decode(encode(synStreamFrame)) should_== synStreamFrame
    }

    "Decode a SynReplyFrame" in {
      decode(encode(synReplyFrame)) should_== synReplyFrame
    }

    "Decode a RstFrame" in {
      decode(encode(rstFrame)) should_== rstFrame
    }

    "Decode a SettingsFrame" in {
      decode(encode(settingsFrame)) should_== settingsFrame
    }

    "Decode a PingFrame" in {
      decode(encode(pingFrame)) should_== pingFrame
    }

    "Decode a GoAwayFrame" in {
      decode(encode(goAwayFrame)) should_== goAwayFrame
    }

    "Decode a HeadersFrame" in {
      decode(encode(headersFrame)) should_== headersFrame
    }
    
    "Decode a WindowUpdate frame" in {
      decode(encode(windowUpdateFrame)) should_== windowUpdateFrame
    }
  }

}
