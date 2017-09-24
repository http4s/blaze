package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.util.BufferTools
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

class Http2FrameEncoderSpec extends Specification with Mockito with Http2SpecTools {

  def mockListener() = mock[Http2FrameListener]

  // increase the ID by 25 to lift it out of the normal h2 exception codes
  def ReturnTag(id: Int): Error = Error(Http2Exception.errorGenerator(id.toLong + 25l).goaway())

  "Http2FrameEncoder" >> {
    "not fragments data frames if they fit" >> {
      val tools = new Http2MockTools(true)
      val handler = mockListener()
      val decoder = new Http2FrameDecoder(tools.peerSettings, handler)

      tools.peerSettings.maxFrameSize = 15 // technically an illegal size...

      // Frame 1 `endStream = true`
      val data1 = BufferTools.joinBuffers(tools.frameEncoder.dataFrame(1, true, zeroBuffer(15)))
      handler.onDataFrame(1, true, zeroBuffer(15), 15) returns ReturnTag(1)
      decoder.decodeBuffer(data1) must_== ReturnTag(1)

      // Frame 2 `endStream = false`
      val data2 = BufferTools.joinBuffers(tools.frameEncoder.dataFrame(1, false, zeroBuffer(15)))
      handler.onDataFrame(1, false, zeroBuffer(15), 15) returns ReturnTag(2)
      decoder.decodeBuffer(data2) must_== ReturnTag(2)
    }

    "fragments data frames if they exceed the mySettings.maxFrameSize" >> {
      val tools = new Http2MockTools(true)
      val handler = mockListener()
      val decoder = new Http2FrameDecoder(tools.peerSettings, handler)

      tools.peerSettings.maxFrameSize = 10 // technically an illegal size...

      // `endStream = true`
      val data1 = BufferTools.joinBuffers(tools.frameEncoder.dataFrame(1, true, zeroBuffer(15)))

      // Frame 1
      handler.onDataFrame(1, false, zeroBuffer(10), 10) returns ReturnTag(1)
      decoder.decodeBuffer(data1) must_== ReturnTag(1)

      // Frame 2
      handler.onDataFrame(1, true, zeroBuffer(5), 5) returns ReturnTag(2)
      decoder.decodeBuffer(data1) must_== ReturnTag(2)

      // `endStream = false`
      val data2 = BufferTools.joinBuffers(tools.frameEncoder.dataFrame(1, false, zeroBuffer(15)))

      // Frame 1
      handler.onDataFrame(1, false, zeroBuffer(10), 10) returns ReturnTag(3)
      decoder.decodeBuffer(data2) must_== ReturnTag(3)

      // Frame 2
      handler.onDataFrame(1, false, zeroBuffer(5), 5) returns ReturnTag(4)
      decoder.decodeBuffer(data2) must_== ReturnTag(4)
    }

    "not fragment headers if they fit into one frame" >> {
      val tools = new Http2MockTools(true) {
        override lazy val headerEncoder: HeaderEncoder = new HeaderEncoder(100) {
          override def encodeHeaders(hs: Seq[(String, String)]): ByteBuffer = zeroBuffer(15)
        }
      }
      val handler = mockListener()
      val decoder = new Http2FrameDecoder(tools.peerSettings, handler)

      tools.peerSettings.maxFrameSize = 15 // technically an illegal size...
      // `endStream = true`
      val data1 = BufferTools.joinBuffers(tools.frameEncoder.headerFrame(1, Priority.NoPriority, true, Nil))

      handler.onHeadersFrame(1, Priority.NoPriority, true, true, zeroBuffer(15)) returns ReturnTag(1)
      decoder.decodeBuffer(data1) must_== ReturnTag(1)

      // `endStream = false`
      val data2 = BufferTools.joinBuffers(tools.frameEncoder.headerFrame(1, Priority.NoPriority, false, Nil))

      handler.onHeadersFrame(1, Priority.NoPriority, true, false, zeroBuffer(15)) returns ReturnTag(2)
      decoder.decodeBuffer(data2) must_== ReturnTag(2)
    }

    "fragment headers if they don't fit into one frame" >> {
      val tools = new Http2MockTools(true) {
        override lazy val headerEncoder: HeaderEncoder = new HeaderEncoder(100) {
          override def encodeHeaders(hs: Seq[(String, String)]): ByteBuffer = zeroBuffer(15)
        }
      }
      val handler = mockListener()
      val decoder = new Http2FrameDecoder(tools.peerSettings, handler)

      tools.peerSettings.maxFrameSize = 10 // technically an illegal size...
      val data = BufferTools.joinBuffers(tools.frameEncoder.headerFrame(1, Priority.NoPriority, true, Nil))

      handler.inHeaderSequence returns false
      handler.onHeadersFrame(1, Priority.NoPriority, endHeaders = false, endStream = true, zeroBuffer(10)) returns ReturnTag(1)
      decoder.decodeBuffer(data) must_== ReturnTag(1)

      handler.onHeadersFrame(any, any, any, any, any) returns null
      handler.onContinuationFrame(1, endHeaders = true, zeroBuffer(5)) returns ReturnTag(2)
      handler.inHeaderSequence returns true
      decoder.decodeBuffer(data) must_== ReturnTag(2)
    }

    "fragment headers considers priority info size" >> {
      val tools = new Http2MockTools(true) {
        override lazy val headerEncoder: HeaderEncoder = new HeaderEncoder(100) {
          override def encodeHeaders(hs: Seq[(String, String)]): ByteBuffer = zeroBuffer(10)
        }
      }
      val handler = mockListener()
      val decoder = new Http2FrameDecoder(tools.peerSettings, handler)

      tools.peerSettings.maxFrameSize = 10 // technically an illegal size...
      val p = Priority.Dependent(2, true, 12)
      val data = BufferTools.joinBuffers(tools.frameEncoder.headerFrame(1, p, true, Nil))

      handler.onHeadersFrame(1, p, endHeaders = false, endStream = true, zeroBuffer(5)) returns ReturnTag(1)
      handler.inHeaderSequence returns false
      decoder.decodeBuffer(data) must_== ReturnTag(1)

      handler.onHeadersFrame(any, any, any, any, any) returns null
      handler.onContinuationFrame(1, endHeaders = true, zeroBuffer(5)) returns ReturnTag(2)
      handler.inHeaderSequence returns true
      decoder.decodeBuffer(data) must_== ReturnTag(2)
    }
  }
}
