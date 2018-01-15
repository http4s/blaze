package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http._
import org.http4s.blaze.http.http2.mocks.{MockFrameListener, MockHeaderAggregatingFrameListener}
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

class HeaderAggregatingFrameListenerSpec extends Specification {

  import BufferTools._
  import CodecUtils._

  private val Halt = Error(Http2SessionException(-1, "test"))

  private def encodeHeaders(hs: Seq[(String, String)]): ByteBuffer =
    new HeaderEncoder(Http2Settings.default.headerTableSize).encodeHeaders(hs)

  "HEADERS frame with compressors" should {
    def dec(sId: Int, p: Priority, es: Boolean, hs: Headers): TestFrameDecoder =
      decoder(new MockHeaderAggregatingFrameListener {
        override def onCompleteHeadersFrame(streamId: Int,
                                            priority: Priority,
                                            end_stream: Boolean,
                                            headers: Headers): Result = {
          sId must_== streamId
          p must_== priority
          es must_== end_stream
          hs must_== headers
          Halt
        }
      })

    "Make a simple round trip" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val hsBuf = encodeHeaders(hs)

      val bs = FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, true, true, 0, hsBuf)

      dec(1, Priority.NoPriority, true, hs).decodeBuffer(BufferTools.joinBuffers(bs)) must_== Halt
    }

    "Make a round trip with a continuation frame" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val hsBuf = encodeHeaders(hs)
      val first = BufferTools.takeSlice(hsBuf, hsBuf.remaining() - 1)
      val bs = FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, false, true, 0, first)

      val decoder = dec(1, Priority.NoPriority, true, hs)

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = FrameSerializer.mkContinuationFrame(1, true, hsBuf)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
      decoder.listener.inHeaderSequence must beFalse
    }

    "Make a round trip with a continuation frame" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val bs = FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, false, true, 0, BufferTools.emptyBuffer)

      val decoder = dec(1, Priority.NoPriority, true, hs)

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = FrameSerializer.mkContinuationFrame(1, true, encodeHeaders(hs))
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
      decoder.listener.inHeaderSequence must beFalse
    }

    "Make a round trip with a continuation frame" in {
      val hs1 = Seq("foo" -> "bar")
      val hs2 = Seq("biz" -> "baz")
      val bs = FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, false, true, 0, encodeHeaders(hs1))

      val decoder = dec(1, Priority.NoPriority, true, hs1 ++ hs2)

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = FrameSerializer.mkContinuationFrame(1, true, encodeHeaders(hs2))
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
      decoder.listener.inHeaderSequence must beFalse
    }

    "Fail on invalid frame sequence (bad streamId)" in {
      val bs = FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, false, true, 0, BufferTools.emptyBuffer)

      val decoder = dec(1, Priority.NoPriority, true, Seq())

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = FrameSerializer.mkContinuationFrame(2, true, BufferTools.emptyBuffer)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must beAnInstanceOf[Error]
    }

    "Fail on invalid frame sequence (wrong frame type)" in {
      val bs = FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, false, true, 0, BufferTools.emptyBuffer)

      val decoder = dec(1, Priority.NoPriority, true, Seq())

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = FrameSerializer.mkWindowUpdateFrame(2, 1)
      decoder.decodeBuffer(bs2) must beAnInstanceOf[Error]
    }
  }

  "PUSH_PROMISE frame" should {
    def dat = mkData(20)

    def dec(sId: Int, pId: Int, end_h: Boolean) =
      decoder(new MockFrameListener(false) {
        override def onPushPromiseFrame(streamId: Int,
                                        promisedId: Int,
                                        end_headers: Boolean,
                                        data: ByteBuffer): Result = {

          sId must_== streamId
          pId must_== promisedId
          end_h must_== end_headers
          assert(compare(data::Nil, dat::Nil))
          Continue
        }
      })

    "make round trip" in {
      val buff1 = BufferTools.joinBuffers(FrameSerializer.mkPushPromiseFrame(1, 2, true, 0, dat))
      dec(1, 2, true).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0
    }

    "handle padding" in {
      val paddingSize = 10
      val buff = joinBuffers(FrameSerializer.mkPushPromiseFrame(1, 2, true, paddingSize, dat))
      dec(1, 2, true).decodeBuffer(buff) must_== Continue
    }

    "fail on bad stream ID" in {
      FrameSerializer.mkPushPromiseFrame(0 , 2, true, -10, dat) must throwA[Exception]
    }

    "fail on bad promised stream ID" in {
      FrameSerializer.mkPushPromiseFrame(1 , 0, true, -10, dat) must throwA[Exception]
      FrameSerializer.mkPushPromiseFrame(1 , 3, true, -10, dat) must throwA[Exception]
    }

    "fail on bad padding" in {
      FrameSerializer.mkPushPromiseFrame(1 , 2, true, -10, dat) must throwA[Exception]
      FrameSerializer.mkPushPromiseFrame(1 , 2, true, 500, dat) must throwA[Exception]
    }
  }

  "PUSH_PROMISE frame with header decoder" should {
    def dec(sId: Int, pId: Int, hs: Headers) =
      decoder(new MockHeaderAggregatingFrameListener {
        override def onCompletePushPromiseFrame(streamId: Int,
                                                promisedId: Int,
                                                headers: Headers): Result = {
          sId must_== streamId
          pId must_== promisedId
          hs must_== headers
          Halt
        }
      })

    "Make a simple round trip" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val hsBuf = encodeHeaders(hs)
      val bs = FrameSerializer.mkPushPromiseFrame(1, 2, true, 0, hsBuf)

      val decoder = dec(1, 2, hs)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Halt
      decoder.listener.inHeaderSequence must beFalse
    }

    "Make a round trip with a continuation frame" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val hsBuf = encodeHeaders(hs)
      val bs = FrameSerializer.mkPushPromiseFrame(1, 2, false, 0, hsBuf)

      val decoder = dec(1, 2, hs)

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = FrameSerializer.mkContinuationFrame(1, true, BufferTools.emptyBuffer)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
      decoder.listener.inHeaderSequence must beFalse
    }

    "Make a round trip with a zero leanth HEADERS and a continuation frame" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")

      val bs = FrameSerializer.mkPushPromiseFrame(1, 2, false, 0, BufferTools.emptyBuffer)

      val decoder = dec(1, 2, hs)

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = FrameSerializer.mkContinuationFrame(1, true, encodeHeaders(hs))
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
      decoder.listener.inHeaderSequence must beFalse
    }

    "Make a round trip with a continuation frame" in {
      val hs1 = Seq("foo" -> "bar")
      val hs2 = Seq("biz" -> "baz")
      val bs = FrameSerializer.mkPushPromiseFrame(1, 2, false, 0, encodeHeaders(hs1))

      val decoder = dec(1, 2, hs1 ++ hs2)

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = FrameSerializer.mkContinuationFrame(1, true, encodeHeaders(hs2))
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
      decoder.listener.inHeaderSequence must beFalse
    }

    "Fail on invalid frame sequence (wrong streamId)" in {
      val hs1 = Seq("foo" -> "bar")
      val hs2 = Seq("biz" -> "baz")
      val bs = FrameSerializer.mkPushPromiseFrame(1, 2, false, 0, encodeHeaders(hs1))

      val decoder = dec(1, 2, hs1 ++ hs2)

      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      val bs2 = FrameSerializer.mkContinuationFrame(2, true, encodeHeaders(hs2))
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must beAnInstanceOf[Error]
    }

    "Fail on invalid frame sequence (wrong frame type)" in {
      val hs1 = Seq("foo" -> "bar")
      val bs = FrameSerializer.mkPushPromiseFrame(1, 2, false, 0, encodeHeaders(hs1))

      val decoder = dec(1, 2, hs1)

      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      val bs2 = FrameSerializer.mkWindowUpdateFrame(2, 1)
      decoder.decodeBuffer(bs2) must beAnInstanceOf[Error]
    }
  }
}
