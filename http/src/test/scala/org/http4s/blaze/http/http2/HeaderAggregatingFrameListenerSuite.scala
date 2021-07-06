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

import org.http4s.blaze.http._
import org.http4s.blaze.http.http2.mocks.{MockFrameListener, MockHeaderAggregatingFrameListener}
import org.http4s.blaze.testkit.BlazeTestSuite
import org.http4s.blaze.util.BufferTools

class HeaderAggregatingFrameListenerSuite extends BlazeTestSuite {
  import BufferTools._
  import CodecUtils._

  private val Halt = Error(Http2SessionException(-1, "test"))

  private def encodeHeaders(hs: Seq[(String, String)]): ByteBuffer =
    new HeaderEncoder(Http2Settings.default.headerTableSize).encodeHeaders(hs)

  private def mkDecoder(sId: Int, p: Priority, es: Boolean, hs: Headers): TestFrameDecoder =
    decoder(new MockHeaderAggregatingFrameListener {
      override def onCompleteHeadersFrame(
          streamId: Int,
          priority: Priority,
          end_stream: Boolean,
          headers: Headers): Result = {
        assertEquals(sId, streamId)
        assertEquals(p, priority)
        assertEquals(es, end_stream)
        assertEquals(hs, headers)
        Halt
      }
    })

  test("HEADERS frame with compressors should make a simple round trip") {
    val hs = Seq("foo" -> "bar", "biz" -> "baz")
    val hsBuf = encodeHeaders(hs)

    val bs = FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, true, true, 0, hsBuf)

    assertEquals(
      mkDecoder(1, Priority.NoPriority, true, hs).decodeBuffer(BufferTools.joinBuffers(bs)),
      Halt)
  }

  test("HEADERS frame with compressors should make a round trip with a continuation frame") {
    val hs = Seq("foo" -> "bar", "biz" -> "baz")
    val hsBuf = encodeHeaders(hs)
    val first = BufferTools.takeSlice(hsBuf, hsBuf.remaining() - 1)
    val bs = FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, false, true, 0, first)

    val decoder = mkDecoder(1, Priority.NoPriority, true, hs)

    assertEquals(decoder.listener.inHeaderSequence, false)
    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs)), Continue)

    assert(decoder.listener.inHeaderSequence)

    val bs2 = FrameSerializer.mkContinuationFrame(1, true, hsBuf)
    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs2)), Halt)
    assertEquals(decoder.listener.inHeaderSequence, false)
  }

  test("HEADERS frame with compressors should make a round trip with a continuation frame") {
    val hs = Seq("foo" -> "bar", "biz" -> "baz")
    val bs =
      FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, false, true, 0, BufferTools.emptyBuffer)

    val decoder = mkDecoder(1, Priority.NoPriority, true, hs)

    assertEquals(decoder.listener.inHeaderSequence, false)
    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs)), Continue)

    assert(decoder.listener.inHeaderSequence)

    val bs2 = FrameSerializer.mkContinuationFrame(1, true, encodeHeaders(hs))
    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs2)), Halt)
    assertEquals(decoder.listener.inHeaderSequence, false)
  }

  test("HEADERS frame with compressors should make a round trip with a continuation frame") {
    val hs1 = Seq("foo" -> "bar")
    val hs2 = Seq("biz" -> "baz")
    val bs =
      FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, false, true, 0, encodeHeaders(hs1))

    val decoder = mkDecoder(1, Priority.NoPriority, true, hs1 ++ hs2)

    assertEquals(decoder.listener.inHeaderSequence, false)
    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs)), Continue)

    assert(decoder.listener.inHeaderSequence)

    val bs2 = FrameSerializer.mkContinuationFrame(1, true, encodeHeaders(hs2))
    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs2)), Halt)
    assertEquals(decoder.listener.inHeaderSequence, false)
  }

  test("HEADERS frame with compressors should fail on invalid frame sequence (bad streamId)") {
    val bs =
      FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, false, true, 0, BufferTools.emptyBuffer)

    val decoder = mkDecoder(1, Priority.NoPriority, true, Seq())

    assertEquals(decoder.listener.inHeaderSequence, false)
    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs)), Continue)

    assert(decoder.listener.inHeaderSequence)

    val bs2 = FrameSerializer.mkContinuationFrame(2, true, BufferTools.emptyBuffer)
    decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) match {
      case _: Error => ()
      case _ => fail("Unexpected decodeBuffer result")
    }
  }

  test("HEADERS frame with compressors should fail on invalid frame sequence (wrong frame type)") {
    val bs =
      FrameSerializer.mkHeaderFrame(1, Priority.NoPriority, false, true, 0, BufferTools.emptyBuffer)

    val decoder = mkDecoder(1, Priority.NoPriority, true, Seq())

    assertEquals(decoder.listener.inHeaderSequence, false)
    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs)), Continue)

    assert(decoder.listener.inHeaderSequence)

    val bs2 = FrameSerializer.mkWindowUpdateFrame(2, 1)
    decoder.decodeBuffer(bs2) match {
      case _: Error => ()
      case _ => fail("Unexpected decodeBuffer result")
    }
  }

  private def dat = mkData(20)

  private def dec(sId: Int, pId: Int, end_h: Boolean) =
    decoder(new MockFrameListener(false) {
      override def onPushPromiseFrame(
          streamId: Int,
          promisedId: Int,
          end_headers: Boolean,
          data: ByteBuffer): Result = {
        assertEquals(sId, streamId)
        assertEquals(pId, promisedId)
        assertEquals(end_h, end_headers)
        assert(compare(data :: Nil, dat :: Nil))
        Continue
      }
    })

  test("PUSH_PROMISE frame should make round trip") {
    val buff1 = BufferTools.joinBuffers(FrameSerializer.mkPushPromiseFrame(1, 2, true, 0, dat))
    assertEquals(dec(1, 2, true).decodeBuffer(buff1), Continue)
    assertEquals(buff1.remaining(), 0)
  }

  test("PUSH_PROMISE frame should handle padding") {
    val paddingSize = 10
    val buff = joinBuffers(FrameSerializer.mkPushPromiseFrame(1, 2, true, paddingSize, dat))
    assertEquals(dec(1, 2, true).decodeBuffer(buff), Continue)
  }

  test("PUSH_PROMISE frame should fail on bad stream ID") {
    intercept[Throwable](FrameSerializer.mkPushPromiseFrame(0, 2, true, -10, dat))
  }

  test("PUSH_PROMISE frame should fail on bad promised stream ID") {
    intercept[Throwable](FrameSerializer.mkPushPromiseFrame(1, 0, true, -10, dat))
    intercept[Throwable](FrameSerializer.mkPushPromiseFrame(1, 3, true, -10, dat))
  }

  test("PUSH_PROMISE frame should fail on bad padding") {
    intercept[Throwable](FrameSerializer.mkPushPromiseFrame(1, 2, true, -10, dat))
    intercept[Throwable](FrameSerializer.mkPushPromiseFrame(1, 2, true, 500, dat))
  }

  private def mkDec(sId: Int, pId: Int, hs: Headers) =
    decoder(new MockHeaderAggregatingFrameListener {
      override def onCompletePushPromiseFrame(
          streamId: Int,
          promisedId: Int,
          headers: Headers): Result = {
        assertEquals(sId, streamId)
        assertEquals(pId, promisedId)
        assertEquals(hs, headers)
        Halt
      }
    })

  test("PUSH_PROMISE frame with header decoder should make a simple round trip") {
    val hs = Seq("foo" -> "bar", "biz" -> "baz")
    val hsBuf = encodeHeaders(hs)
    val bs = FrameSerializer.mkPushPromiseFrame(1, 2, true, 0, hsBuf)

    val decoder = mkDec(1, 2, hs)
    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs)), Halt)
    assertEquals(decoder.listener.inHeaderSequence, false)
  }

  test(
    "PUSH_PROMISE frame with header decoder should make a round trip with a continuation frame") {
    val hs = Seq("foo" -> "bar", "biz" -> "baz")
    val hsBuf = encodeHeaders(hs)
    val bs = FrameSerializer.mkPushPromiseFrame(1, 2, false, 0, hsBuf)

    val decoder = mkDec(1, 2, hs)

    assertEquals(decoder.listener.inHeaderSequence, false)
    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs)), Continue)

    assert(decoder.listener.inHeaderSequence)

    val bs2 = FrameSerializer.mkContinuationFrame(1, true, BufferTools.emptyBuffer)
    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs2)), Halt)
    assertEquals(decoder.listener.inHeaderSequence, false)
  }

  test(
    "PUSH_PROMISE frame with header decoder should make a round trip with a zero leanth HEADERS and a continuation frame") {
    val hs = Seq("foo" -> "bar", "biz" -> "baz")

    val bs = FrameSerializer.mkPushPromiseFrame(1, 2, false, 0, BufferTools.emptyBuffer)

    val decoder = mkDec(1, 2, hs)

    assertEquals(decoder.listener.inHeaderSequence, false)
    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs)), Continue)

    assert(decoder.listener.inHeaderSequence)

    val bs2 = FrameSerializer.mkContinuationFrame(1, true, encodeHeaders(hs))
    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs2)), Halt)
    assertEquals(decoder.listener.inHeaderSequence, false)
  }

  test(
    "PUSH_PROMISE frame with header decoder should make a round trip with a continuation frame") {
    val hs1 = Seq("foo" -> "bar")
    val hs2 = Seq("biz" -> "baz")
    val bs = FrameSerializer.mkPushPromiseFrame(1, 2, false, 0, encodeHeaders(hs1))

    val decoder = mkDec(1, 2, hs1 ++ hs2)

    assertEquals(decoder.listener.inHeaderSequence, false)
    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs)), Continue)

    assert(decoder.listener.inHeaderSequence)

    val bs2 = FrameSerializer.mkContinuationFrame(1, true, encodeHeaders(hs2))
    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs2)), Halt)
    assertEquals(decoder.listener.inHeaderSequence, false)
  }

  test(
    "PUSH_PROMISE frame with header decoder should fail on invalid frame sequence (wrong streamId)") {
    val hs1 = Seq("foo" -> "bar")
    val hs2 = Seq("biz" -> "baz")
    val bs = FrameSerializer.mkPushPromiseFrame(1, 2, false, 0, encodeHeaders(hs1))

    val decoder = mkDec(1, 2, hs1 ++ hs2)

    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs)), Continue)

    val bs2 = FrameSerializer.mkContinuationFrame(2, true, encodeHeaders(hs2))
    decoder.decodeBuffer(BufferTools.joinBuffers(bs2)) match {
      case _: Error => ()
      case _ => fail("Unexpected decodeBuffer result")
    }
  }

  test(
    "PUSH_PROMISE frame with header decoder should fail on invalid frame sequence (wrong frame type)") {
    val hs1 = Seq("foo" -> "bar")
    val bs = FrameSerializer.mkPushPromiseFrame(1, 2, false, 0, encodeHeaders(hs1))

    val decoder = mkDec(1, 2, hs1)

    assertEquals(decoder.decodeBuffer(BufferTools.joinBuffers(bs)), Continue)

    val bs2 = FrameSerializer.mkWindowUpdateFrame(2, 1)
    decoder.decodeBuffer(bs2) match {
      case _: Error => ()
      case _ => fail("Unexpected decodeBuffer result")
    }
  }
}
