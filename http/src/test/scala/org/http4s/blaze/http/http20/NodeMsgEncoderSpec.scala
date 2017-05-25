package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.http.http20.NodeMsg.{DataFrame, HeadersFrame}
import org.http4s.blaze.util.BufferTools
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

import scala.collection.mutable.ArrayBuffer


class NodeMsgEncoderSpec extends Specification with Mockito {

  private def headerEncoder(): HeaderEncoder = new HeaderEncoder()

  private def getMockFrameEncoder(): Http20FrameEncoder = mock[Http20FrameEncoder]

  val fourBytes = Array[Byte](1,2,3,4)

  "NodeMessageEncoder" should {

    // TODO: respect byte boundaries

    "Encode a DataFrame" in {
      val mockFrameEnc = getMockFrameEncoder()

      val theseBuffers = List(BufferTools.emptyBuffer)

      mockFrameEnc.mkDataFrame(any/*buffer*/, any /*streamid*/, any /*isLast*/, any /*padding*/) returns theseBuffers

      val encoder = new NodeMsgEncoder(1, mockFrameEnc, headerEncoder())

      val data = DataFrame(true, ByteBuffer.wrap(fourBytes))
      val out = new ArrayBuffer[ByteBuffer]()

      val (flow, unused) = encoder.encodeMessages(Int.MaxValue, Int.MaxValue, Seq(data), out)

      unused.isEmpty must_== true
      flow must_== 4
      out.toList must_== theseBuffers
      there was one(mockFrameEnc).mkDataFrame(ByteBuffer.wrap(fourBytes), 1, true, 0)
    }

    "Encode a HeadersFrame" in {
      val mockFrameEnc = getMockFrameEncoder()

      val theseBuffers = List(BufferTools.emptyBuffer)

      mockFrameEnc.mkHeaderFrame(
        any, // headerData
        any, // streamId
        any, // priority
        any, // endHeaders
        any, // endStream
        any  // padding
      ) returns theseBuffers

      val encoder = new NodeMsgEncoder(1, mockFrameEnc, headerEncoder())

      val hs = Seq("a" -> "a", "b" -> "b")
      val data = HeadersFrame(None, true, hs)
      val out = new ArrayBuffer[ByteBuffer]()

      val (flow, unused) = encoder.encodeMessages(Int.MaxValue, Int.MaxValue, Seq(data), out)

      unused.isEmpty must_== true
      flow must_== 0
      out.toList must_== theseBuffers

      val expectedBuffer = headerEncoder().encodeHeaders(hs)

      there was one(mockFrameEnc).mkHeaderFrame(expectedBuffer, 1, None, true, true, 0)
    }

    "Encode a HeadersFrame that exceeds the max frame size" in {
      val mockFrameEnc = getMockFrameEncoder()

      val theseBuffers = List(BufferTools.emptyBuffer)
      val thoseBuffers = List(ByteBuffer.wrap(fourBytes))

      mockFrameEnc.mkHeaderFrame(
        any, // headerData
        any, // streamId
        any, // priority
        any, // endHeaders
        any, // endStream
        any  // padding
      ) returns theseBuffers

      mockFrameEnc.mkContinuationFrame(
        any, // streamId
        any, // endHeaders
        any  // buffer
      ) returns thoseBuffers

      val encoder = new NodeMsgEncoder(1, mockFrameEnc, headerEncoder())

      val hs = Seq("a" -> "a", "b" -> "b")
      val expectedBuffer = headerEncoder().encodeHeaders(hs)

      val data = HeadersFrame(None, true, hs)
      val out = new ArrayBuffer[ByteBuffer]()

      val (flow, unused) = encoder.encodeMessages(expectedBuffer.remaining() - 1, Int.MaxValue, Seq(data), out)

      unused.isEmpty must_== true
      flow must_== 0
      out.toList must_== (theseBuffers ++ thoseBuffers)

      val firstChunk = BufferTools.takeSlice(expectedBuffer, expectedBuffer.remaining() - 1)

      there was one(mockFrameEnc).mkHeaderFrame(firstChunk, 1, None, false, true, 0)
      there was one(mockFrameEnc).mkContinuationFrame(1, true, expectedBuffer)
    }
  }
}
