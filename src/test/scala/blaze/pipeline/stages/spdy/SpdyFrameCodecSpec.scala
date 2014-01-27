package blaze.pipeline.stages.spdy

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
  
  def windowUpdateFrame = WindowUpdate(2, 4)
  
  def decode(buff: ByteBuffer, requireFull: Boolean = true): SpdyFrame = {
    val h = new SpdyFrameCodec().bufferToMessage(buff).get

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

  "Spdy Types" should {
    "encode DataFrames" in {
      val buffs = dataFrame.encode
      buffs.head.getInt(4) & 0xffffff should equal(5)
      buffs.tail.head should equal (data)
    }

    "encode SynStreamFrames" in {
      val buffs = synStreamFrame.encode()
    }

    "encode SynReplyFrames" in {
      val buffs = synReplyFrame.encode
    }

    "encode RstFrame" in {
      val buffs = rstFrame.encode
    }

    "encode a SettingsFrame" in {
      val buffs = settingsFrame.encode
    }
    
    
  }

  "SpdyFrameCodec" should {
    "Decode a Data frame" in {
      decode(concat(dataFrame.encode)) should equal(dataFrame)
    }

    "Decode a SynFrame" in {
      decode(concat(synStreamFrame.encode)) should equal(synStreamFrame)
    }

    "Decode a SynReplyFrame" in {
      decode(concat(synReplyFrame.encode)) should equal(synReplyFrame)
    }

    "Decode a RstFrame" in {
      decode(concat(rstFrame.encode)) should equal(rstFrame)
    }

    "Decode a SettingsFrame" in {
      decode(concat(settingsFrame.encode)) should equal(settingsFrame)
    }

    "Decode a PingFrame" in {
      decode(concat(pingFrame.encode)) should equal(pingFrame)
    }

    "Decode a GoAwayFrame" in {
      decode(concat(goAwayFrame.encode)) should equal(goAwayFrame)
    }

    "Decode a HeadersFrame" in {
      decode(concat(headersFrame.encode)) should equal(headersFrame)
    }
    
    "Decode a WindowUpdate frame" in {
      decode(concat(windowUpdateFrame.encode)) should equal(windowUpdateFrame)
    }
  }

}
