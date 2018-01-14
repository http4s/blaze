package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.mocks.MockTools
import org.http4s.blaze.util.BufferTools
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

class FrameEncoderSpec extends Specification with Mockito {
  import CodecUtils._

  private def mockListener() = mock[FrameListener]

  // increase the ID by 25 to lift it out of the normal h2 exception codes
  private def ReturnTag(id: Int): Error = Error(Http2Exception.errorGenerator(id.toLong + 25l).goaway())

  "Http2FrameEncoder" >> {
    "not fragment data frames if they fit into a single frame" >> {
      val tools = new MockTools(true)
      val listener = mockListener()
      val decoder = new FrameDecoder(tools.remoteSettings, listener)

      tools.remoteSettings.maxFrameSize = 15 // technically an illegal size...

      // Frame 1 `endStream = true`
      val data1 = BufferTools.joinBuffers(tools.frameEncoder.dataFrame(1, true, zeroBuffer(15)))
      listener.onDataFrame(1, true, zeroBuffer(15), 15) returns ReturnTag(1)
      decoder.decodeBuffer(data1) must_== ReturnTag(1)

      // Frame 2 `endStream = false`
      val data2 = BufferTools.joinBuffers(tools.frameEncoder.dataFrame(1, false, zeroBuffer(15)))
      listener.onDataFrame(1, false, zeroBuffer(15), 15) returns ReturnTag(2)
      decoder.decodeBuffer(data2) must_== ReturnTag(2)
    }

    "fragments data frames if they exceed the localSettings.maxFrameSize" >> {
      val tools = new MockTools(true)
      val listener = mockListener()
      val remoteDecoder = new FrameDecoder(tools.remoteSettings, listener)

      tools.remoteSettings.maxFrameSize = 10 // technically an illegal size...

      // `endStream = true`
      val data1 = BufferTools.joinBuffers(tools.frameEncoder.dataFrame(1, true, zeroBuffer(15)))

      // Frame 1
      listener.onDataFrame(1, false, zeroBuffer(10), 10) returns ReturnTag(1)
      remoteDecoder.decodeBuffer(data1) must_== ReturnTag(1)

      // Frame 2
      listener.onDataFrame(1, true, zeroBuffer(5), 5) returns ReturnTag(2)
      remoteDecoder.decodeBuffer(data1) must_== ReturnTag(2)

      // `endStream = false`
      val data2 = BufferTools.joinBuffers(tools.frameEncoder.dataFrame(1, false, zeroBuffer(15)))

      // Frame 1
      listener.onDataFrame(1, false, zeroBuffer(10), 10) returns ReturnTag(3)
      remoteDecoder.decodeBuffer(data2) must_== ReturnTag(3)

      // Frame 2
      listener.onDataFrame(1, false, zeroBuffer(5), 5) returns ReturnTag(4)
      remoteDecoder.decodeBuffer(data2) must_== ReturnTag(4)
    }

    "not fragment headers if they fit into a single frame" >> {
      val tools = new MockTools(true) {
        override lazy val headerEncoder: HeaderEncoder = new HeaderEncoder(100) {
          override def encodeHeaders(hs: Seq[(String, String)]): ByteBuffer = zeroBuffer(15)
        }
      }
      val listener = mockListener()
      val decoder = new FrameDecoder(tools.remoteSettings, listener)

      tools.remoteSettings.maxFrameSize = 15 // technically an illegal size...
      // `endStream = true`
      val data1 = BufferTools.joinBuffers(tools.frameEncoder.headerFrame(1, Priority.NoPriority, true, Nil))

      listener.onHeadersFrame(1, Priority.NoPriority, true, true, zeroBuffer(15)) returns ReturnTag(1)
      decoder.decodeBuffer(data1) must_== ReturnTag(1)

      // `endStream = false`
      val data2 = BufferTools.joinBuffers(tools.frameEncoder.headerFrame(1, Priority.NoPriority, false, Nil))

      listener.onHeadersFrame(1, Priority.NoPriority, true, false, zeroBuffer(15)) returns ReturnTag(2)
      decoder.decodeBuffer(data2) must_== ReturnTag(2)
    }

    "fragment headers if they don't fit into one frame" >> {
      val tools = new MockTools(true) {
        override lazy val headerEncoder: HeaderEncoder = new HeaderEncoder(100) {
          override def encodeHeaders(hs: Seq[(String, String)]): ByteBuffer = zeroBuffer(15)
        }
      }
      val listener = mockListener()
      val decoder = new FrameDecoder(tools.remoteSettings, listener)

      tools.remoteSettings.maxFrameSize = 10 // technically an illegal size...
      val data = BufferTools.joinBuffers(tools.frameEncoder.headerFrame(1, Priority.NoPriority, true, Nil))

      listener.inHeaderSequence returns false
      listener.onHeadersFrame(1, Priority.NoPriority, endHeaders = false, endStream = true, zeroBuffer(10)) returns ReturnTag(1)
      decoder.decodeBuffer(data) must_== ReturnTag(1)

      listener.onHeadersFrame(any, any, any, any, any) returns null
      listener.onContinuationFrame(1, endHeaders = true, zeroBuffer(5)) returns ReturnTag(2)
      listener.inHeaderSequence returns true
      decoder.decodeBuffer(data) must_== ReturnTag(2)
    }

    "fragmenting HEADERS frames considers priority info size" >> {
      val tools = new MockTools(true) {
        override lazy val headerEncoder: HeaderEncoder = new HeaderEncoder(100) {
          override def encodeHeaders(hs: Seq[(String, String)]): ByteBuffer = zeroBuffer(10)
        }
      }
      val listener = mockListener()
      val decoder = new FrameDecoder(tools.remoteSettings, listener)

      tools.remoteSettings.maxFrameSize = 10 // technically an illegal size...
      val p = Priority.Dependent(2, true, 12)
      val data = BufferTools.joinBuffers(tools.frameEncoder.headerFrame(1, p, true, Nil))

      listener.onHeadersFrame(1, p, endHeaders = false, endStream = true, zeroBuffer(5)) returns ReturnTag(1)
      listener.inHeaderSequence returns false
      decoder.decodeBuffer(data) must_== ReturnTag(1)

      listener.onHeadersFrame(any, any, any, any, any) returns null
      listener.onContinuationFrame(1, endHeaders = true, zeroBuffer(5)) returns ReturnTag(2)
      listener.inHeaderSequence returns true
      decoder.decodeBuffer(data) must_== ReturnTag(2)
    }
  }
}
