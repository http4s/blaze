/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.util

import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.http.http2.SettingsDecoder.SettingsFrame
import org.http4s.blaze.http.http2.bits.Masks
import org.http4s.blaze.http.http2.mocks.{MockFrameListener, MockHeaderAggregatingFrameListener}
import org.http4s.blaze.util.BufferTools._
import org.scalacheck.{Arbitrary, Gen}
import org.specs2.mutable.Specification
import org.specs2.ScalaCheck

class FrameSerializerSpec extends Specification with ScalaCheck {
  import CodecUtils._

  lazy val tfGen: Gen[Boolean] = Gen.oneOf(true, false)

  lazy val genPriority: Gen[Priority.Dependent] = for {
    depId <- Gen.posNum[Int]
    exclusive <- tfGen
    weight <- Gen.choose(1, 256)
  } yield Priority.Dependent(depId, exclusive, weight)

  "DATA frame" should {
    case class DataFrame(streamId: Int, endStream: Boolean, data: ByteBuffer, padding: Int) {
      def flowSize: Int = data.remaining + padding
    }

    implicit lazy val genDataFrame: Arbitrary[DataFrame] = Arbitrary(
      for {
        streamId <- Gen.posNum[Int]
        isLast <- tfGen
        padding <- Gen.choose(0, 256)
        bytes <- Gen.choose(0, 16 * 1024 - padding)
      } yield DataFrame(streamId, isLast, mkData(bytes), padding)
    )

    def dec(dataFrame: DataFrame): TestFrameDecoder =
      decoder(new MockFrameListener(false) {
        override def onDataFrame(
            streamId: Int,
            isLast: Boolean,
            data: ByteBuffer,
            flowSize: Int): Result = {
          streamId must_== dataFrame.streamId
          isLast must_== dataFrame.endStream
          compare(data :: Nil, dataFrame.data.duplicate :: Nil) must beTrue
          data.remaining must_== dataFrame.data.remaining
          dataFrame.flowSize must_== flowSize

          Continue
        }
      })

    "roundtrip" >> prop { dataFrame: DataFrame =>
      val frame = joinBuffers(
        FrameSerializer.mkDataFrame(
          dataFrame.streamId,
          dataFrame.endStream,
          dataFrame.padding,
          dataFrame.data.duplicate()))
      dec(dataFrame).decodeBuffer(frame) == Continue
    }

    "Decode padded buffers" in {
      forall(0 to 256) { i: Int =>
        def dat = mkData(20)
        val frame = joinBuffers(FrameSerializer.mkDataFrame(3, false, i, dat))
        dec(DataFrame(3, false, dat, i)).decodeBuffer(frame) must_== Continue
      }
    }
  }

  // This doesn't check header compression
  "HEADERS frame" should {
    case class HeadersFrame(
        streamId: Int,
        priority: Priority,
        endHeaders: Boolean,
        endStream: Boolean,
        data: ByteBuffer,
        padding: Int) {
      def flowSize: Int = data.remaining + padding
    }

    implicit lazy val arbHeaders: Arbitrary[HeadersFrame] = Arbitrary(
      for {
        streamId <- Gen.posNum[Int]
        hasDep <- tfGen
        priority <- (if (hasDep) genPriority.filter(_.dependentStreamId != streamId)
                     else Gen.const(Priority.NoPriority))
        endHeaders <- tfGen
        endStream <- tfGen
        padding <- Gen.choose(0, 256)
        bytes <- Gen.choose(0, 16 * 1024 - padding - (if (hasDep) 5 else 0))
      } yield HeadersFrame(streamId, priority, endHeaders, endStream, mkData(bytes), padding)
    )

    def dat = mkData(20)

    def dec(headerFrame: HeadersFrame) =
      decoder(new MockFrameListener(false) {
        override def onHeadersFrame(
            streamId: Int,
            priority: Priority,
            end_headers: Boolean,
            end_stream: Boolean,
            buffer: ByteBuffer): Result = {
          headerFrame.streamId must_== streamId
          headerFrame.priority must_== priority
          headerFrame.endHeaders must_== end_headers
          headerFrame.endStream must_== end_stream
          assert(compare(buffer :: Nil, headerFrame.data.duplicate :: Nil))
          Continue
        }
      })

    "roundtrip" >> prop { headerFrame: HeadersFrame =>
      val buff1 = joinBuffers(
        FrameSerializer.mkHeaderFrame(
          headerFrame.streamId,
          headerFrame.priority,
          headerFrame.endHeaders,
          headerFrame.endStream,
          headerFrame.padding,
          headerFrame.data.duplicate))

      dec(headerFrame).decodeBuffer(buff1) must_== Continue
    }

    "make round trip" in {
      val buff1 =
        joinBuffers(FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, true, true, 0, dat))
      dec(HeadersFrame(1, Priority.NoPriority, true, true, dat, 0))
        .decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0

      val priority = Priority.Dependent(3, false, 6)
      val buff2 = joinBuffers(FrameSerializer.mkHeaderFrame(2, priority, true, false, 0, dat))
      dec(HeadersFrame(2, priority, true, false, dat, 0)).decodeBuffer(buff2) must_== Continue
      buff2.remaining() must_== 0
    }

    "handle padding" in {
      val paddingSize = 10
      val buff = joinBuffers(
        FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, true, true, paddingSize, dat))
      dec(HeadersFrame(1, Priority.NoPriority, true, true, dat, paddingSize))
        .decodeBuffer(buff) must_== Continue
    }

    "fail on bad stream ID" in {
      FrameSerializer.mkHeaderFrame(0, Priority.NoPriority, true, true, 0, dat) must throwA[
        Exception]
    }

    "fail on bad padding" in {
      FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, true, true, -10, dat) must throwA[
        Exception]
    }
  }

  "PRIORITY frame" should {
    case class PriorityFrame(streamId: Int, priority: Priority.Dependent)

    def dec(priorityFrame: PriorityFrame) =
      decoder(new MockFrameListener(false) {
        override def onPriorityFrame(streamId: Int, priority: Priority.Dependent): Result = {
          priorityFrame.streamId must_== streamId
          priorityFrame.priority must_== priority
          Continue
        }
      })

    implicit lazy val arbPriority = Arbitrary(
      for {
        streamId <- Gen.posNum[Int]
        p <- genPriority.filter(_.dependentStreamId != streamId)
      } yield PriorityFrame(streamId, p)
    )

    "roundtrip" >> prop { p: PriorityFrame =>
      val buff1 = FrameSerializer.mkPriorityFrame(p.streamId, p.priority)
      dec(p).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0
    }

    "fail to make a bad dependent Priority" in {
      Priority.Dependent(1, true, 500) must throwA[Exception]
      Priority.Dependent(1, true, -500) must throwA[Exception]
    }

    "fail on bad streamId" in {
      FrameSerializer.mkPriorityFrame(0, Priority.Dependent(1, true, 0)) must throwA[Exception]
    }

    "fail on bad stream dependency" in {
      Priority.Dependent(0, true, 0) must throwA[Exception]
    }
  }

  "RST_STREAM frame" should {
    case class RstFrame(streamId: Int, code: Long)

    implicit lazy val arbRstFrame: Arbitrary[RstFrame] = Arbitrary(
      for {
        streamId <- Gen.posNum[Int]
        code <- Gen.choose(Int.MinValue, Int.MaxValue).map(_ & Masks.INT32)
      } yield RstFrame(streamId, code)
    )

    def dec(rstFrame: RstFrame) =
      decoder(new MockFrameListener(false) {
        override def onRstStreamFrame(streamId: Int, code: Long): Result = {
          rstFrame.streamId must_== streamId
          rstFrame.code must_== code
          Continue
        }
      })

    "roundtrip" >> prop { rst: RstFrame =>
      val buff1 = FrameSerializer.mkRstStreamFrame(rst.streamId, rst.code)
      dec(rst).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0
    }

    "fail on bad stream Id" in {
      FrameSerializer.mkRstStreamFrame(0, 1) must throwA[Exception]
    }
  }

  "SETTINGS frame" should {
    def dec(settingsFrame: SettingsFrame) =
      decoder(new MockFrameListener(false) {
        override def onSettingsFrame(settings: Option[Seq[Setting]]): Result = {
          settingsFrame.settings must_== settings
          Continue
        }
      })

    "roundtrip" >> prop { ack: Boolean =>
      val settings = if (ack) None else Some((0 until 100).map(i => Setting(i, i + 3)))

      val buff1 = settings match {
        case None => FrameSerializer.mkSettingsAckFrame()
        case Some(settings) => FrameSerializer.mkSettingsFrame(settings)
      }

      dec(SettingsFrame(settings)).decodeBuffer(buff1) must_== Continue
      buff1.remaining() must_== 0
    }
  }

  "PING frame" should {
    case class PingFrame(ack: Boolean, data: Array[Byte])

    implicit lazy val arbPing: Arbitrary[PingFrame] = Arbitrary(
      for {
        ack <- tfGen
        bytes <- Gen.listOfN(8, Gen.choose(Byte.MinValue, Byte.MaxValue))
      } yield PingFrame(ack, bytes.toArray)
    )

    def dec(pingFrame: PingFrame) =
      decoder(new MockHeaderAggregatingFrameListener {
        override def onPingFrame(ack: Boolean, data: Array[Byte]): Result = {
          pingFrame.ack must_== ack
          assert(util.Arrays.equals(pingFrame.data, data))
          Continue
        }
      })

    "make a simple round trip" >> prop { pingFrame: PingFrame =>
      val pingBuffer = FrameSerializer.mkPingFrame(pingFrame.ack, pingFrame.data)
      dec(pingFrame).decodeBuffer(pingBuffer) must_== Continue
    }
  }

  "GOAWAY frame" should {
    case class GoAwayFrame(lastStream: Int, err: Long, data: Array[Byte])

    implicit lazy val arbGoAway: Arbitrary[GoAwayFrame] = Arbitrary(
      for {
        lastStream <- Gen.choose(0, Int.MaxValue)
        err <- Gen.choose(0L, 0XFFFFFFFFL)
        dataSize <- Gen.choose(0, 256)
        bytes <- Gen.listOfN(dataSize, Gen.choose(Byte.MinValue, Byte.MaxValue))
      } yield GoAwayFrame(lastStream, err, bytes.toArray)
    )

    def dec(goAway: GoAwayFrame) =
      decoder(new MockHeaderAggregatingFrameListener {
        override def onGoAwayFrame(
            lastStream: Int,
            errorCode: Long,
            debugData: Array[Byte]): Result = {
          goAway.lastStream must_== lastStream
          goAway.err must_== errorCode
          assert(util.Arrays.equals(goAway.data, debugData))
          Continue
        }
      })

    "roundtrip" >> prop { goAway: GoAwayFrame =>
      val encodedGoAway = FrameSerializer.mkGoAwayFrame(goAway.lastStream, goAway.err, goAway.data)
      dec(goAway).decodeBuffer(encodedGoAway) must_== Continue
    }
  }

  "WINDOW_UPDATE frame" should {
    case class WindowUpdateFrame(streamId: Int, increment: Int)

    implicit lazy val arbWindowUpdate: Arbitrary[WindowUpdateFrame] = Arbitrary(
      for {
        streamId <- Gen.choose(0, Int.MaxValue)
        increment <- Gen.posNum[Int]
      } yield WindowUpdateFrame(streamId, increment)
    )

    def dec(update: WindowUpdateFrame) =
      decoder(new MockHeaderAggregatingFrameListener {
        override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Result = {
          update.streamId must_== streamId
          update.increment must_== sizeIncrement
          Continue
        }
      })

    "roundtrip" >> prop { updateFrame: WindowUpdateFrame =>
      val updateBuffer =
        FrameSerializer.mkWindowUpdateFrame(updateFrame.streamId, updateFrame.increment)
      dec(updateFrame).decodeBuffer(updateBuffer) must_== Continue
    }

    "fail on invalid stream" in {
      FrameSerializer.mkWindowUpdateFrame(-1, 10) must throwA[Exception]
    }

    "fail on invalid window update" in {
      FrameSerializer.mkWindowUpdateFrame(1, 0) must throwA[Exception]
      FrameSerializer.mkWindowUpdateFrame(1, -1) must throwA[Exception]
    }
  }
}
