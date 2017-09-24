package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.util

import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.util.BufferTools._
import org.specs2.mutable.Specification

class Http2FrameSerializerSpec extends Specification {
  import CodecUtils._

  "DATA frame" should {
    val DataSize = 20
    def dat = mkData(DataSize)

    def dec(sId: Int, endStream: Boolean, padding: Int): TestHttp2FrameDecoder =
      decoder(new MockFrameListener(false) {
        override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flowSize: Int): Http2Result = {
          streamId must_== sId
          isLast must_== endStream
          compare(data::Nil, dat::Nil) must beTrue
          data.remaining must_== DataSize
          padding + data.remaining must_== flowSize

          Continue
        }
    })

    def roundTrip(streamId: Int, endStream: Boolean, padding: Int): Http2Result = {
      val frame = joinBuffers(Http2FrameSerializer.mkDataFrame(streamId, endStream, padding, dat))
      dec(streamId, endStream, padding).decodeBuffer(frame)
    }

    "make round trip" in {
      roundTrip(streamId = 1, endStream = true, padding = 0) must_== Continue
    }

    "Decode 'END_STREAM flag" in {
      roundTrip(streamId = 3, endStream = false, padding = 0) must_== Continue
    }

    "Decode padded buffers" in {
      forall(0 to 256) { i: Int =>
        roundTrip(streamId = 3, endStream = false, padding = i) must_== Continue
      }
    }
  }

  // This doesn't check header compression
  "HEADERS frame" should {
    def dat = mkData(20)

    def dec(sId: Int, pri: Priority, end_h: Boolean,  end_s: Boolean) =
      decoder(new MockFrameListener(false) {
        override def onHeadersFrame(streamId: Int,
                                    priority: Priority,
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
      val buff1 = joinBuffers(Http2FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, true, true, 0, dat))
      dec(1, Priority.NoPriority, true, true).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0

      val priority = Priority.Dependent(3, false, 6)

      val buff2 = joinBuffers(Http2FrameSerializer.mkHeaderFrame(2, priority, true, false, 0, dat))
      dec(2, priority, true, false).decodeBuffer(buff2) must_== Continue
      buff2.remaining() must_== 0
    }

    "preserve padding" in {
      val buff = addBonus(Http2FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, true, true, 0, dat))
      dec(1, Priority.NoPriority, true, true).decodeBuffer(buff) must_== Continue
      buff.remaining() must_== bonusSize
    }

    "fail on bad stream ID" in {
      Http2FrameSerializer.mkHeaderFrame(
        0, Priority.NoPriority, true, true, 0, dat) must throwA[Exception]
    }

    "fail on bad padding" in {
      Http2FrameSerializer.mkHeaderFrame(
        1, Priority.NoPriority, true, true, -10, dat) must throwA[Exception]
    }
  }

  "PRIORITY frame" should {
    def dec(sId: Int, p: Priority.Dependent) =
      decoder(new MockFrameListener(false) {
        override def onPriorityFrame(streamId: Int, priority: Priority.Dependent): Http2Result = {
          sId must_== streamId
          p must_== priority
          Continue
        }
      })

    "make a round trip" in {
      val buff1 = Http2FrameSerializer.mkPriorityFrame(1, Priority.Dependent(2, true, 1))
      dec(1, Priority.Dependent(2, true, 1)).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0

      val buff2 = Http2FrameSerializer.mkPriorityFrame(1, Priority.Dependent(2, false, 10))
      dec(1, Priority.Dependent(2, false, 10)).decodeBuffer(buff2) must_== Continue
      buff2.remaining() must_== 0
    }

    "fail on bad priority" in {
      Priority.Dependent(1, true, 500) must throwA[Exception]
      Priority.Dependent(1, true, -500) must throwA[Exception]
    }

    "fail on bad streamId" in {
      Http2FrameSerializer.mkPriorityFrame(0, Priority.Dependent(1, true, 0)) must throwA[Exception]
    }

    "fail on bad stream dependency" in {
      Priority.Dependent(0, true, 0) must throwA[Exception]
    }
  }

  "RST_STREAM frame" should {
    def dec(sId: Int, c: Int) =
      decoder(new MockFrameListener(false) {

        override def onRstStreamFrame(streamId: Int, code: Long): Http2Result = {
          sId must_== streamId
          c must_== code
          Continue
        }
      })

    "make round trip" in {
      val buff1 = Http2FrameSerializer.mkRstStreamFrame(1, 0)
      dec(1, 0).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0
    }

    "fail on bad stream Id" in {
      Http2FrameSerializer.mkRstStreamFrame(0, 1) must throwA[Exception]
    }
  }

  "SETTINGS frame" should {
    def dec(a: Boolean, s: Seq[Setting]) =
      decoder(new MockFrameListener(false) {
        override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = {
          s must_== settings
          a must_== ack
          Continue
        }
      })

    "make a round trip" in {
      val settings = (0 until 100).map(i => Setting(i, i + 3))

      val buff1 = Http2FrameSerializer.mkSettingsFrame(settings)
      dec(false, settings).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0
    }
  }

  "PING frame" should {
    def dec(d: ByteBuffer, a: Boolean) =
      decoder(new MockHeaderAggregatingFrameListener {
        override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result = {
          ack must_== a
          assert(compare(ByteBuffer.wrap(data)::Nil, d::Nil))
          Continue
        }
      })

    "make a simple round trip" in {
      val data = Array(1,2,3,4,5,6,7,8).map(_.toByte)

      val bs1 = Http2FrameSerializer.mkPingFrame(false, data)
      dec(ByteBuffer.wrap(data), false).decodeBuffer(bs1) must_== Continue

      val bs2 = Http2FrameSerializer.mkPingFrame(true, data)
      dec(ByteBuffer.wrap(data), true).decodeBuffer(bs2) must_== Continue
    }
  }

  "GOAWAY frame" should {
    def dec(sId: Int, err: Long, d: Array[Byte]) =
      decoder(new MockHeaderAggregatingFrameListener {


        override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: Array[Byte]): Http2Result = {
          sId must_== lastStream
          err must_== errorCode
          assert(util.Arrays.equals(d, debugData))
          Continue
        }
      })

    "make a simple round trip" in {
      val bs1 = Http2FrameSerializer.mkGoAwayFrame(1, 0, Array[Byte]())
      dec(1, 0, Array[Byte]()).decodeBuffer(joinBuffers(bs1)) must_== Continue
    }

    "make a round trip with data" in {
      def data = byteData

      val bs1 = Http2FrameSerializer.mkGoAwayFrame(1, 0, data)
      dec(1, 0, data).decodeBuffer(joinBuffers(bs1)) must_== Continue
    }
  }

  "WINDOW_UPDATE frame" should {
    def dec(sId: Int, inc: Int) =
      decoder(new MockHeaderAggregatingFrameListener {
        override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Http2Result = {
          sId must_== streamId
          inc must_== sizeIncrement
          Continue
        }
      })

    "make a simple round trip" in {
      val bs1 = Http2FrameSerializer.mkWindowUpdateFrame(0, 10)
      dec(0, 10).decodeBuffer(bs1) must_== Continue
    }

    "fail on invalid stream" in {
      Http2FrameSerializer.mkWindowUpdateFrame(-1, 10) must throwA[Exception]
    }

    "fail on invalid window update" in {
      Http2FrameSerializer.mkWindowUpdateFrame(1, 0) must throwA[Exception]
      Http2FrameSerializer.mkWindowUpdateFrame(1, -1) must throwA[Exception]
      println(Integer.MAX_VALUE)
      Http2FrameSerializer.mkWindowUpdateFrame(1, Integer.MAX_VALUE + 1) must throwA[Exception]
    }
  }
}
