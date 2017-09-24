package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http._
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

class HeaderAggregatingFrameHandlerSpec extends Specification {

  import CodecUtils._

  private def encodeHeaders(hs: Seq[(String, String)]): ByteBuffer =
    new HeaderEncoder(Http2Settings.default.headerTableSize).encodeHeaders(hs)

  "HEADERS frame with compressors" should {
    def dec(sId: Int, p: Priority, es: Boolean, hs: Headers): TestHttp2FrameDecoder =
      decoder(new MockHeaderAggregatingFrameListener {
        override def onCompleteHeadersFrame(streamId: Int,
                                            priority: Priority,
                                            end_stream: Boolean,
                                            headers: Headers): Http2Result = {
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

      val bs = Http2FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, true, true, 0.toByte, hsBuf)

      dec(1, Priority.NoPriority, true, hs).decodeBuffer(BufferTools.joinBuffers(bs)) must_== Halt
    }

    "Make a round trip with a continuation frame" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val hsBuf = encodeHeaders(hs)
      val first = BufferTools.takeSlice(hsBuf, hsBuf.remaining() - 1)
      val bs = Http2FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, false, true, 0.toByte, first)

      val decoder = dec(1, Priority.NoPriority, true, hs)

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = Http2FrameSerializer.mkContinuationFrame(1, true, hsBuf)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
    }

    "Make a round trip with a continuation frame" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val bs = Http2FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, false, true, 0.toByte, BufferTools.emptyBuffer)

      val decoder = dec(1, Priority.NoPriority, true, hs)

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = Http2FrameSerializer.mkContinuationFrame(1, true, encodeHeaders(hs))
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
    }

    "Make a round trip with a continuation frame" in {
      val hs1 = Seq("foo" -> "bar")
      val hs2 = Seq("biz" -> "baz")
      val bs = Http2FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, false, true, 0.toByte, encodeHeaders(hs1))

      val decoder = dec(1, Priority.NoPriority, true, hs1 ++ hs2)

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = Http2FrameSerializer.mkContinuationFrame(1, true, encodeHeaders(hs2))
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
    }

    "Fail on invalid frame sequence (bad streamId)" in {
      val bs = Http2FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, false, true, 0.toByte, BufferTools.emptyBuffer)

      val decoder = dec(1, Priority.NoPriority, true, Seq())

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = Http2FrameSerializer.mkContinuationFrame(2, true, BufferTools.emptyBuffer)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must beAnInstanceOf[Error]
    }

    "Fail on invalid frame sequence (wrong frame type)" in {
      val bs = Http2FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, false, true, 0.toByte, BufferTools.emptyBuffer)

      val decoder = dec(1, Priority.NoPriority, true, Seq())

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = Http2FrameSerializer.mkWindowUpdateFrame(2, 1)
      decoder.decodeBuffer(bs2) must beAnInstanceOf[Error]
    }
  }

  "PUSH_PROMISE frame" should {
    def dat = mkData(20)

    def dec(sId: Int, pId: Int, end_h: Boolean) =
      decoder(new MockFrameHandler(false) {
        override def onPushPromiseFrame(streamId: Int,
                                        promisedId: Int,
                                        end_headers: Boolean,
                                        data: ByteBuffer): Http2Result = {

          sId must_== streamId
          pId must_== promisedId
          end_h must_== end_headers
          assert(compare(data::Nil, dat::Nil))
          Continue
        }
      })

    "make round trip" in {
      val buff1 = BufferTools.joinBuffers(Http2FrameSerializer.mkPushPromiseFrame(1, 2, true, 0, dat))
      dec(1, 2, true).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0
    }

    "preserve padding" in {
      val buff = addBonus(Http2FrameSerializer.mkPushPromiseFrame(1, 2, true, bonusSize, dat))
      dec(1, 2, true).decodeBuffer(buff) must_== Continue
      buff.remaining() must_== bonusSize
    }

    "fail on bad stream ID" in {
      Http2FrameSerializer.mkPushPromiseFrame(0 , 2, true, -10, dat) must throwA[Exception]
    }

    "fail on bad promised stream ID" in {
      Http2FrameSerializer.mkPushPromiseFrame(1 , 0, true, -10, dat) must throwA[Exception]
      Http2FrameSerializer.mkPushPromiseFrame(1 , 3, true, -10, dat) must throwA[Exception]
    }

    "fail on bad padding" in {
      Http2FrameSerializer.mkPushPromiseFrame(1 , 2, true, -10, dat) must throwA[Exception]
      Http2FrameSerializer.mkPushPromiseFrame(1 , 2, true, 500, dat) must throwA[Exception]
    }
  }

  "PUSH_PROMISE frame with header decoder" should {
    def dec(sId: Int, pId: Int, hs: Headers) =
      decoder(new MockHeaderAggregatingFrameListener {
        override def onCompletePushPromiseFrame(streamId: Int,
                                                promisedId: Int,
                                                headers: Headers): Http2Result = {
          sId must_== streamId
          pId must_== promisedId
          hs must_== headers
          Halt
        }
      })

    "Make a simple round trip" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val hsBuf = encodeHeaders(hs)
      val bs = Http2FrameSerializer.mkPushPromiseFrame(1, 2, true, 0, hsBuf)

      dec(1, 2, hs).decodeBuffer(BufferTools.joinBuffers(bs)) must_== Halt
    }

    "Make a round trip with a continuation frame" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val hsBuf = encodeHeaders(hs)
      val bs = Http2FrameSerializer.mkPushPromiseFrame(1, 2, false, 0, hsBuf)

      val decoder = dec(1, 2, hs)

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = Http2FrameSerializer.mkContinuationFrame(1, true, BufferTools.emptyBuffer)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
    }

    "Make a round trip with a continuation frame" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")

      val bs = Http2FrameSerializer.mkPushPromiseFrame(1, 2, false, 0, BufferTools.emptyBuffer)

      val decoder = dec(1, 2, hs)

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = Http2FrameSerializer.mkContinuationFrame(1, true, encodeHeaders(hs))
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
    }

    "Make a round trip with a continuation frame" in {
      val hs1 = Seq("foo" -> "bar")
      val hs2 = Seq("biz" -> "baz")
      val bs = Http2FrameSerializer.mkPushPromiseFrame(1, 2, false, 0, encodeHeaders(hs1))

      val decoder = dec(1, 2, hs1 ++ hs2)

      decoder.listener.inHeaderSequence must beFalse
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.listener.inHeaderSequence must beTrue

      val bs2 = Http2FrameSerializer.mkContinuationFrame(1, true, encodeHeaders(hs2))
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
    }

    "Fail on invalid frame sequence (wrong streamId)" in {
      val hs1 = Seq("foo" -> "bar")
      val hs2 = Seq("biz" -> "baz")
      val bs = Http2FrameSerializer.mkPushPromiseFrame(1, 2, false, 0, encodeHeaders(hs1))

      val decoder = dec(1, 2, hs1 ++ hs2)

      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      val bs2 = Http2FrameSerializer.mkContinuationFrame(2, true, encodeHeaders(hs2))
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must beAnInstanceOf[Error]
    }

    "Fail on invalid frame sequence (wrong frame type)" in {
      val hs1 = Seq("foo" -> "bar")
      val bs = Http2FrameSerializer.mkPushPromiseFrame(1, 2, false, 0, encodeHeaders(hs1))

      val decoder = dec(1, 2, hs1)

      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      val bs2 = Http2FrameSerializer.mkWindowUpdateFrame(2, 1)
      decoder.decodeBuffer(bs2) must beAnInstanceOf[Error]
    }
  }
}
