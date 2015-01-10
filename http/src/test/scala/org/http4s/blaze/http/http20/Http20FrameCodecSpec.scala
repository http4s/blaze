package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.http.http20.Settings.Setting
import org.http4s.blaze.util.BufferTools
import org.http4s.blaze.util.BufferTools._

import org.specs2.mutable.Specification


class Http20FrameCodecSpec extends Specification {

  def mkData(size: Int): ByteBuffer = {
    val s = "The quick brown fox jumps over the lazy dog".getBytes()
    val buff = ByteBuffer.allocate(size)
    while(buff.hasRemaining) buff.put(s, 0, math.min(buff.remaining(), s.length))
    buff.flip()
    buff
  }

  def compare(s1: Seq[ByteBuffer], s2: Seq[ByteBuffer]): Boolean = {
    val b1 = joinBuffers(s1)
    val b2 = joinBuffers(s2)

    if (b1.remaining() != b2.remaining()) false
    else {
      def go(): Boolean = {
        if (b1.hasRemaining) {
          if (b1.get() == b2.get()) go()
          else false
        }
        else true
      }

      go()
    }
  }

  val bonusSize = 10

  def addBonus(buffers: Seq[ByteBuffer]): ByteBuffer = {
    joinBuffers(buffers :+ ByteBuffer.allocate(bonusSize))
  }

  def decoder(h: FrameHandler, inHeaders: Boolean = false) = new Http20FrameDecoder {
    def handler = h

  }

  def encoder = new Http20FrameEncoder {}

  "DATA frame" should {

    def dat = mkData(20)

    def dec(sId: Int, isL: Boolean, padding: Int) = decoder(new MockFrameHandler(false) {
      override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flowSize: Int): Http2Result = {
        streamId must_== sId
        isLast must_== isL
        compare(data::Nil, dat::Nil) must_== true
        padding must_== flowSize

        Continue
      }
    })

    "make round trip" in {
      val buff1 = joinBuffers(encoder.mkDataFrame(dat, 1, true, 0))
      dec(1, true, dat.remaining()).decodeBuffer(buff1) must_== Continue
    }

    "Decode 'END_STREAM flag" in {
      // payload size is buffer + padding + 1 byte
      val buff2 = joinBuffers(encoder.mkDataFrame(dat, 3, false, 100))
      dec(3, false, dat.remaining() + 100).decodeBuffer(buff2) must_== Continue
    }

    "Decode padded buffers" in {
      val buff3 = addBonus(encoder.mkDataFrame(dat, 1, true, 100))
      dec(1, true, dat.remaining() + 100).decodeBuffer(buff3) must_== Continue
      buff3.remaining() must_== bonusSize
    }


  }

  // This doesn't check header compression
  "HEADERS frame" should {
    def dat = mkData(20)

    def dec(sId: Int, pri: Option[Priority], end_h: Boolean,  end_s: Boolean) =
      decoder(new MockFrameHandler(false) {
        override def onHeadersFrame(streamId: Int,
                                    priority: Option[Priority],
                                    end_headers: Boolean,
                                    end_stream: Boolean,
                                    buffer: ByteBuffer): Http2Result = {
          sId must_== streamId
          pri must_== priority
          end_h must_== end_headers
          end_s must_== end_stream
          assert(compare(buffer::Nil, dat::Nil))
          Continue
        }
    })

    "make round trip" in {
      val buff1 = joinBuffers(encoder.mkHeaderFrame(dat, 1, None, true, true, 0))
      dec(1, None, true, true).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0

      val buff2 = joinBuffers(encoder.mkHeaderFrame(dat, 2, Some(Priority(3, false, 6)), true, false, 0))
      dec(2, Some(Priority(3, false, 6)), true, false).decodeBuffer(buff2) must_== Continue
      buff2.remaining() must_== 0
    }

    "preserve padding" in {
      val buff = addBonus(encoder.mkHeaderFrame(dat, 1, None, true, true, 0))
      dec(1, None, true, true).decodeBuffer(buff) must_== Continue
      buff.remaining() must_== bonusSize
    }

    "fail on bad stream ID" in {
      encoder.mkHeaderFrame(dat,0 , None, true, true, 0) must throwA[Exception]
    }

    "fail on bad padding" in {
      encoder.mkHeaderFrame(dat,1 , None, true, true, -10) must throwA[Exception]
      encoder.mkHeaderFrame(dat,1 , None, true, true, 500) must throwA[Exception]
    }
  }

  "PRIORITY frame" should {
    def dec(sId: Int, p: Priority) =
      decoder(new MockFrameHandler(false) {
        override def onPriorityFrame(streamId: Int, priority: Priority): Http2Result = {
          sId must_== streamId
          p must_== priority
          Continue
        }
      })

    "make a round trip" in {
      val buff1 = encoder.mkPriorityFrame(1, Priority(1, true, 1))
      dec(1, Priority(1, true, 1)).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0

      val buff2 = encoder.mkPriorityFrame(1, Priority(1, false, 10))
      dec(1, Priority(1, false, 10)).decodeBuffer(buff2) must_== Continue
      buff2.remaining() must_== 0
    }

    "fail on bad priority" in {
      Priority(1, true, 500) must throwA[Exception]
      Priority(1, true, -500) must throwA[Exception]
    }

    "fail on bad streamId" in {
      encoder.mkPriorityFrame(0, Priority(1, true, 0)) must throwA[Exception]
    }

    "fail on bad stream dependency" in {
      Priority(0, true, 0) must throwA[Exception]
    }
  }

  "RST_STREAM frame" should {
    def dec(sId: Int, c: Int) =
      decoder(new MockFrameHandler(false) {

        override def onRstStreamFrame(streamId: Int, code: Int): Http2Result = {
          sId must_== streamId
          c must_== code
          Continue
        }
      })

    "make round trip" in {
      val buff1 = encoder.mkRstStreamFrame(1, 0)
      dec(1, 0).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0
    }

    "fail on bad stream Id" in {
      encoder.mkRstStreamFrame(0, 1) must throwA[Exception]
    }
  }

  "SETTINGS frame" should {
    def dec(a: Boolean, s: Seq[Setting]) =
      decoder(new MockFrameHandler(false) {
        override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = {
          s must_== settings
          a must_== ack
          Continue
        }
      })

    "make a round trip" in {
      val settings = (0 until 100).map(i => Setting(i, i + 3))

      val buff1 = encoder.mkSettingsFrame(false, settings)
      dec(false, settings).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0
    }

    "reject settings on ACK" in {
      val settings = (0 until 100).map(i => Setting(i, i + 3))
      encoder.mkSettingsFrame(true, settings) must throwA[Exception]
    }
  }

  def hencoder = new HeaderHttp20Encoder with Http20FrameEncoder {
    override type Headers = Seq[(String,String)]
    override protected val headerEncoder: HeaderEncoder[Headers] = new TupleHeaderEncoder()
  }

  "HEADERS frame with compressors" should {
    def dec(sId: Int, p: Option[Priority], es: Boolean, hs: Seq[(String, String)]) =
      decoder(new MockDecodingFrameHandler {
        override def onCompleteHeadersFrame(streamId: Int,
                                            priority: Option[Priority],
                                            end_stream: Boolean,
                                            headers: Seq[(String,String)]): Http2Result = {
          sId must_== streamId
          p must_== priority
          es must_== end_stream
          hs must_== headers
          Halt
        }
      })

    "Make a simple round trip" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val bs = hencoder.mkHeaderFrame(1, None, true, true, 0, hs)

      dec(1, None, true, hs).decodeBuffer(BufferTools.joinBuffers(bs)) must_== Halt
    }

    "Make a round trip with a continuation frame" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val bs = hencoder.mkHeaderFrame(1, None, false, true, 0, hs)

      val decoder = dec(1, None, true, hs)

      decoder.inHeaderSequence() must_== false
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.inHeaderSequence() must_== true

      val bs2 = hencoder.mkContinuationFrame(1, true, Nil)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
    }

    "Make a round trip with a continuation frame" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val bs = hencoder.mkHeaderFrame(1, None, false, true, 0, Nil)

      val decoder = dec(1, None, true, hs)

      decoder.inHeaderSequence() must_== false
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.inHeaderSequence() must_== true

      val bs2 = hencoder.mkContinuationFrame(1, true, hs)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
    }

    "Make a round trip with a continuation frame" in {
      val hs1 = Seq("foo" -> "bar")
      val hs2 = Seq("biz" -> "baz")
      val bs = hencoder.mkHeaderFrame(1, None, false, true, 0, hs1)

      val decoder = dec(1, None, true, hs1 ++ hs2)

      decoder.inHeaderSequence() must_== false
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.inHeaderSequence() must_== true

      val bs2 = hencoder.mkContinuationFrame(1, true, hs2)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
    }

    "Fail on invalid frame sequence (bad streamId)" in {
      val hs1 = Seq("foo" -> "bar")
      val hs2 = Seq("biz" -> "baz")
      val bs = hencoder.mkHeaderFrame(1, None, false, true, 0, hs1)

      val decoder = dec(1, None, true, hs1 ++ hs2)

      decoder.inHeaderSequence() must_== false
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.inHeaderSequence() must_== true

      val bs2 = hencoder.mkContinuationFrame(2, true, hs2)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must beAnInstanceOf[Error]
    }

    "Fail on invalid frame sequence (wrong frame type)" in {
      val hs1 = Seq("foo" -> "bar")
      val hs2 = Seq("biz" -> "baz")
      val bs = hencoder.mkHeaderFrame(1, None, false, true, 0, hs1)

      val decoder = dec(1, None, true, hs1 ++ hs2)

      decoder.inHeaderSequence() must_== false
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.inHeaderSequence() must_== true

      val bs2 = hencoder.mkWindowUpdateFrame(2, 1)
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
        val buff1 = joinBuffers(encoder.mkPushPromiseFrame(1, 2, true, 0, dat))
        dec(1, 2, true).decodeBuffer(buff1) must_== Continue
        buff1.remaining() must_== 0
      }

      "preserve padding" in {
        val buff = addBonus(encoder.mkPushPromiseFrame(1, 2, true, bonusSize, dat))
        dec(1, 2, true).decodeBuffer(buff) must_== Continue
        buff.remaining() must_== bonusSize
      }

      "fail on bad stream ID" in {
        encoder.mkPushPromiseFrame(0 , 2, true, -10, dat) must throwA[Exception]
      }

      "fail on bad promised stream ID" in {
        encoder.mkPushPromiseFrame(1 , 0, true, -10, dat) must throwA[Exception]
        encoder.mkPushPromiseFrame(1 , 3, true, -10, dat) must throwA[Exception]
      }

      "fail on bad padding" in {
        encoder.mkPushPromiseFrame(1 , 2, true, -10, dat) must throwA[Exception]
        encoder.mkPushPromiseFrame(1 , 2, true, 500, dat) must throwA[Exception]
      }
    }

  "PUSH_PROMISE frame with header decoder" should {
    def dec(sId: Int, pId: Int, hs: Seq[(String, String)]) =
      decoder(new MockDecodingFrameHandler {
        override def onCompletePushPromiseFrame(streamId: Int,
                                              promisedId: Int,
                                                 headers: HeaderType): Http2Result = {
          sId must_== streamId
          pId must_== promisedId
          hs must_== headers
          Halt
        }
      })

    "Make a simple round trip" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val bs = hencoder.mkPushPromiseFrame(1, 2, true, 0, hs)

      println(bs)

      dec(1, 2, hs).decodeBuffer(BufferTools.joinBuffers(bs)) must_== Halt
    }

    "Make a round trip with a continuation frame" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val bs = hencoder.mkPushPromiseFrame(1, 2, false, 0, hs)

      val decoder = dec(1, 2, hs)

      decoder.inHeaderSequence() must_== false
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.inHeaderSequence() must_== true

      val bs2 = hencoder.mkContinuationFrame(1, true, Nil)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
    }

    "Make a round trip with a continuation frame" in {
      val hs = Seq("foo" -> "bar", "biz" -> "baz")
      val bs = hencoder.mkPushPromiseFrame(1, 2, false, 0, Nil)

      val decoder = dec(1, 2, hs)

      decoder.inHeaderSequence() must_== false
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.inHeaderSequence() must_== true

      val bs2 = hencoder.mkContinuationFrame(1, true, hs)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
    }

    "Make a round trip with a continuation frame" in {
      val hs1 = Seq("foo" -> "bar")
      val hs2 = Seq("biz" -> "baz")
      val bs = hencoder.mkPushPromiseFrame(1, 2, false, 0, hs1)

      val decoder = dec(1, 2, hs1 ++ hs2)

      decoder.inHeaderSequence() must_== false
      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      decoder.inHeaderSequence() must_== true

      val bs2 = hencoder.mkContinuationFrame(1, true, hs2)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must_== Halt
    }

    "Fail on invalid frame sequence (wrong streamId)" in {
      val hs1 = Seq("foo" -> "bar")
      val hs2 = Seq("biz" -> "baz")
      val bs = hencoder.mkPushPromiseFrame(1, 2, false, 0, hs1)

      val decoder = dec(1, 2, hs1 ++ hs2)

      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      val bs2 = hencoder.mkContinuationFrame(2, true, hs2)
      decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) must beAnInstanceOf[Error]
    }

    "Fail on invalid frame sequence (wrong frame type)" in {
      val hs1 = Seq("foo" -> "bar")
      val bs = hencoder.mkPushPromiseFrame(1, 2, false, 0, hs1)

      val decoder = dec(1, 2, hs1)

      decoder.decodeBuffer(BufferTools.joinBuffers(bs)) must_== Continue

      val bs2 = hencoder.mkWindowUpdateFrame(2, 1)
      decoder.decodeBuffer(bs2) must beAnInstanceOf[Error]
    }
  }

  "PING frame" should {
    def dec(d: ByteBuffer, a: Boolean) =
      decoder(new MockDecodingFrameHandler {
        override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result = {
          ack must_== a
          assert(compare(ByteBuffer.wrap(data)::Nil, d::Nil))
          Continue
        }
      })

    "make a simple round trip" in {
      val data = Array(1,2,3,4,5,6,7,8).map(_.toByte)

      val bs1 = encoder.mkPingFrame(false, data)
      dec(ByteBuffer.wrap(data), false).decodeBuffer(bs1) must_== Continue

      val bs2 = encoder.mkPingFrame(true, data)
      dec(ByteBuffer.wrap(data), true).decodeBuffer(bs2) must_== Continue
    }
  }

  "GOAWAY frame" should {
    def dec(sId: Int, err: Long, d: ByteBuffer) =
      decoder(new MockDecodingFrameHandler {


        override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Http2Result = {
          sId must_== lastStream
          err must_== errorCode
          assert(compare(d::Nil, debugData::Nil))
          Continue
        }
      })

    "make a simple round trip" in {
      val bs1 = encoder.mkGoAwayFrame(1, 0, emptyBuffer)
      dec(1, 0, emptyBuffer).decodeBuffer(joinBuffers(bs1)) must_== Continue
    }

    "make a round trip with data" in {
      def data = mkData(20)

      val bs1 = encoder.mkGoAwayFrame(1, 0, data)
      dec(1, 0, data).decodeBuffer(joinBuffers(bs1)) must_== Continue
    }
  }

  "WINDOW_UPDATE frame" should {
    def dec(sId: Int, inc: Int) =
      decoder(new MockDecodingFrameHandler {
        override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Http2Result = {
          sId must_== streamId
          inc must_== sizeIncrement
          Continue
        }
      })

    "make a simple round trip" in {
      val bs1 = encoder.mkWindowUpdateFrame(0, 10)
      dec(0, 10).decodeBuffer(bs1) must_== Continue
    }

    "fail on invalid stream" in {
      encoder.mkWindowUpdateFrame(-1, 10) must throwA[Exception]
    }

    "fail on invalid window update" in {
      encoder.mkWindowUpdateFrame(1, 0) must throwA[Exception]
      encoder.mkWindowUpdateFrame(1, -1) must throwA[Exception]
      println(Integer.MAX_VALUE)
      encoder.mkWindowUpdateFrame(1, Integer.MAX_VALUE + 1) must throwA[Exception]
    }

  }
}
