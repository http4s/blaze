/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.util

import munit.ScalaCheckSuite
import org.http4s.blaze.http.http2.bits.Masks
import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.http.http2.mocks.{MockFrameListener, MockHeaderAggregatingFrameListener}
import org.http4s.blaze.http.http2.SettingsDecoder.SettingsFrame
import org.http4s.blaze.testkit.BlazeTestSuite
import org.http4s.blaze.util.BufferTools._
import org.scalacheck.Prop._
import org.scalacheck.{Arbitrary, Gen}

class FrameSerializerSuite extends BlazeTestSuite with ScalaCheckSuite {
  import CodecUtils._

  lazy val tfGen: Gen[Boolean] = Gen.oneOf(true, false)

  lazy val genPriority: Gen[Priority.Dependent] = for {
    depId <- Gen.posNum[Int]
    exclusive <- tfGen
    weight <- Gen.choose(1, 256)
  } yield Priority.Dependent(depId, exclusive, weight)

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
          flowSize: Int): org.http4s.blaze.http.http2.Result = {
        assertEquals(streamId, dataFrame.streamId)
        assertEquals(isLast, dataFrame.endStream)
        assert(compare(data :: Nil, dataFrame.data.duplicate :: Nil))
        assertEquals(data.remaining, dataFrame.data.remaining)
        assertEquals(dataFrame.flowSize, flowSize)

        Continue
      }
    })

  property("DATA frame should roundtrip") {
    forAll { (dataFrame: DataFrame) =>
      val frame = joinBuffers(
        FrameSerializer.mkDataFrame(
          dataFrame.streamId,
          dataFrame.endStream,
          dataFrame.padding,
          dataFrame.data.duplicate()))
      dec(dataFrame).decodeBuffer(frame) == Continue
    }
  }

  test("DATA frame should decode padded buffers") {
    assert((0 to 256).forall { (i: Int) =>
      def dat = mkData(20)
      val frame = joinBuffers(FrameSerializer.mkDataFrame(3, false, i, dat))
      dec(DataFrame(3, false, dat, i)).decodeBuffer(frame) == Continue
    })
  }

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
      priority <-
        (if (hasDep) genPriority.filter(_.dependentStreamId != streamId)
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
          buffer: ByteBuffer): org.http4s.blaze.http.http2.Result = {
        assertEquals(headerFrame.streamId, streamId)
        assertEquals(headerFrame.priority, priority)
        assertEquals(headerFrame.endHeaders, end_headers)
        assertEquals(headerFrame.endStream, end_stream)
        assert(compare(buffer :: Nil, headerFrame.data.duplicate :: Nil))

        Continue
      }
    })

  // This doesn't check header compression
  property("HEADERS frame should roundtrip") {
    forAll { (headerFrame: HeadersFrame) =>
      val buff1 = joinBuffers(
        FrameSerializer.mkHeaderFrame(
          headerFrame.streamId,
          headerFrame.priority,
          headerFrame.endHeaders,
          headerFrame.endStream,
          headerFrame.padding,
          headerFrame.data.duplicate))

      assertEquals(dec(headerFrame).decodeBuffer(buff1), Continue)
    }
  }

  test("HEADERS frame should make round trip") {
    val buff1 =
      joinBuffers(FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, true, true, 0, dat))
    assertEquals(
      dec(HeadersFrame(1, Priority.NoPriority, true, true, dat, 0))
        .decodeBuffer(buff1),
      Continue)
    assertEquals(buff1.remaining(), 0)

    val priority = Priority.Dependent(3, false, 6)
    val buff2 = joinBuffers(FrameSerializer.mkHeaderFrame(2, priority, true, false, 0, dat))
    assertEquals(dec(HeadersFrame(2, priority, true, false, dat, 0)).decodeBuffer(buff2), Continue)
    assertEquals(buff2.remaining(), 0)
  }

  test("HEADERS frame should handle padding") {
    val paddingSize = 10
    val buff = joinBuffers(
      FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, true, true, paddingSize, dat))
    assertEquals(
      dec(HeadersFrame(1, Priority.NoPriority, true, true, dat, paddingSize))
        .decodeBuffer(buff),
      Continue)
  }

  test("HEADERS frame should fail on bad stream ID") {
    intercept[Throwable](
      FrameSerializer
        .mkHeaderFrame(0, Priority.NoPriority, true, true, 0, dat))
  }

  test("HEADERS frame should fail on bad padding") {
    intercept[Throwable](
      FrameSerializer
        .mkHeaderFrame(1, Priority.NoPriority, true, true, -10, dat))
  }

  case class PriorityFrame(streamId: Int, priority: Priority.Dependent)

  def dec(priorityFrame: PriorityFrame) =
    decoder(new MockFrameListener(false) {
      override def onPriorityFrame(
          streamId: Int,
          priority: Priority.Dependent): org.http4s.blaze.http.http2.Result = {
        assertEquals(priorityFrame.streamId, streamId)
        assertEquals(priorityFrame.priority, priority)
        Continue
      }
    })

  implicit lazy val arbPriority: Arbitrary[PriorityFrame] = Arbitrary(
    for {
      streamId <- Gen.posNum[Int]
      p <- genPriority.filter(_.dependentStreamId != streamId)
    } yield PriorityFrame(streamId, p)
  )

  property("PRIORITY frame should roundtrip") {
    forAll { (p: PriorityFrame) =>
      val buff1 = FrameSerializer.mkPriorityFrame(p.streamId, p.priority)
      assertEquals(dec(p).decodeBuffer(buff1), Continue)
      assertEquals(buff1.remaining(), 0)
    }
  }

  test("PRIORITY frame should fail to make a bad dependent Priority") {
    intercept[Throwable](Priority.Dependent(1, true, 500))
    intercept[Throwable](Priority.Dependent(1, true, -500))
  }

  test("PRIORITY frame should fail on bad streamId") {
    intercept[Throwable](FrameSerializer.mkPriorityFrame(0, Priority.Dependent(1, true, 0)))
  }

  test("PRIORITY frame should fail on bad stream dependency") {
    intercept[Throwable](Priority.Dependent(0, true, 0))
  }

  case class RstFrame(streamId: Int, code: Long)

  implicit lazy val arbRstFrame: Arbitrary[RstFrame] = Arbitrary(
    for {
      streamId <- Gen.posNum[Int]
      code <- Gen.choose(Int.MinValue, Int.MaxValue).map(_ & Masks.INT32)
    } yield RstFrame(streamId, code)
  )

  def dec(rstFrame: RstFrame) =
    decoder(new MockFrameListener(false) {
      override def onRstStreamFrame(
          streamId: Int,
          code: Long): org.http4s.blaze.http.http2.Result = {
        assertEquals(rstFrame.streamId, streamId)
        assertEquals(rstFrame.code, code)
        Continue
      }
    })

  property("RST_STREAM frame should roundtrip") {
    forAll { (rst: RstFrame) =>
      val buff1 = FrameSerializer.mkRstStreamFrame(rst.streamId, rst.code)
      assertEquals(dec(rst).decodeBuffer(buff1), Continue)
      assertEquals(buff1.remaining(), 0)
    }
  }

  test("RST_STREAM frame should fail on bad stream Id") {
    intercept[Throwable](FrameSerializer.mkRstStreamFrame(0, 1))
  }

  def dec(settingsFrame: SettingsFrame) =
    decoder(new MockFrameListener(false) {
      override def onSettingsFrame(
          settings: Option[Seq[Setting]]): org.http4s.blaze.http.http2.Result = {
        assertEquals(settingsFrame.settings, settings)
        Continue
      }
    })

  property("SETTINGS frame should roundtrip") {
    forAll { (ack: Boolean) =>
      val settings = if (ack) None else Some((0 until 100).map(i => Setting(i, i + 3)))

      val buff1 = settings match {
        case None => FrameSerializer.mkSettingsAckFrame()
        case Some(settings) => FrameSerializer.mkSettingsFrame(settings)
      }

      assertEquals(dec(SettingsFrame(settings)).decodeBuffer(buff1), Continue)
      assertEquals(buff1.remaining(), 0)
    }
  }

  case class PingFrame(ack: Boolean, data: Array[Byte])

  implicit lazy val arbPing: Arbitrary[PingFrame] = Arbitrary(
    for {
      ack <- tfGen
      bytes <- Gen.listOfN(8, Gen.choose(Byte.MinValue, Byte.MaxValue))
    } yield PingFrame(ack, bytes.toArray)
  )

  def dec(pingFrame: PingFrame) =
    decoder(new MockHeaderAggregatingFrameListener {
      override def onPingFrame(
          ack: Boolean,
          data: Array[Byte]): org.http4s.blaze.http.http2.Result = {
        assertEquals(pingFrame.ack, ack)
        assert(util.Arrays.equals(pingFrame.data, data))
        Continue
      }
    })

  property("PING frame should make a simple round trip") {
    forAll { (pingFrame: PingFrame) =>
      val pingBuffer = FrameSerializer.mkPingFrame(pingFrame.ack, pingFrame.data)
      assertEquals(dec(pingFrame).decodeBuffer(pingBuffer), Continue)
    }
  }

  case class GoAwayFrame(lastStream: Int, err: Long, data: Array[Byte])

  implicit lazy val arbGoAway: Arbitrary[GoAwayFrame] = Arbitrary(
    for {
      lastStream <- Gen.choose(0, Int.MaxValue)
      err <- Gen.choose(0L, 0xffffffffL)
      dataSize <- Gen.choose(0, 256)
      bytes <- Gen.listOfN(dataSize, Gen.choose(Byte.MinValue, Byte.MaxValue))
    } yield GoAwayFrame(lastStream, err, bytes.toArray)
  )

  def dec(goAway: GoAwayFrame) =
    decoder(new MockHeaderAggregatingFrameListener {
      override def onGoAwayFrame(
          lastStream: Int,
          errorCode: Long,
          debugData: Array[Byte]): org.http4s.blaze.http.http2.Result = {
        assertEquals(goAway.lastStream, lastStream)
        assertEquals(goAway.err, errorCode)
        assert(util.Arrays.equals(goAway.data, debugData))
        Continue
      }
    })

  property("GOAWAY frame should roundtrip") {
    forAll { (goAway: GoAwayFrame) =>
      val encodedGoAway = FrameSerializer.mkGoAwayFrame(goAway.lastStream, goAway.err, goAway.data)
      assertEquals(dec(goAway).decodeBuffer(encodedGoAway), Continue)
    }
  }

  case class WindowUpdateFrame(streamId: Int, increment: Int)

  implicit lazy val arbWindowUpdate: Arbitrary[WindowUpdateFrame] = Arbitrary(
    for {
      streamId <- Gen.choose(0, Int.MaxValue)
      increment <- Gen.posNum[Int]
    } yield WindowUpdateFrame(streamId, increment)
  )

  def dec(update: WindowUpdateFrame) =
    decoder(new MockHeaderAggregatingFrameListener {
      override def onWindowUpdateFrame(
          streamId: Int,
          sizeIncrement: Int): org.http4s.blaze.http.http2.Result = {
        assertEquals(update.streamId, streamId)
        assertEquals(update.increment, sizeIncrement)
        Continue
      }
    })

  property("WINDOW_UPDATE frame should roundtrip") {
    forAll { (updateFrame: WindowUpdateFrame) =>
      val updateBuffer =
        FrameSerializer.mkWindowUpdateFrame(updateFrame.streamId, updateFrame.increment)
      assertEquals(dec(updateFrame).decodeBuffer(updateBuffer), Continue)
    }
  }

  test("WINDOW_UPDATE frame should fail on invalid stream") {
    intercept[Throwable](FrameSerializer.mkWindowUpdateFrame(-1, 10))
  }

  test("WINDOW_UPDATE frame should fail on invalid window update") {
    intercept[Throwable](FrameSerializer.mkWindowUpdateFrame(1, 0))
    intercept[Throwable](FrameSerializer.mkWindowUpdateFrame(1, -1))
  }
}
