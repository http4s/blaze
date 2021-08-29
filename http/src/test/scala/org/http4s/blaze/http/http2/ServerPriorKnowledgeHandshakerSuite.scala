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

import org.http4s.blaze.http.http2.mocks.MockByteBufferHeadStage
import org.http4s.blaze.http.http2.server.ServerPriorKnowledgeHandshaker
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.testkit.BlazeTestSuite
import org.http4s.blaze.util.BufferTools

import scala.util.{Failure, Success}

class ServerPriorKnowledgeHandshakerSuite extends BlazeTestSuite {
  private def makeHandshaker(
      localSettings: ImmutableHttp2Settings): ServerPriorKnowledgeHandshaker = {
    val flowStrategy = new DefaultFlowStrategy(localSettings)
    new ServerPriorKnowledgeHandshaker(
      localSettings = localSettings,
      flowStrategy = flowStrategy,
      nodeBuilder = _ => ???
    )
  }

  test("A ServerPriorKnowledgeHandshaker can perform a handshake") {
    val localSettings = Http2Settings.default
    val head = new MockByteBufferHeadStage
    val handshaker = makeHandshaker(localSettings)
    LeafBuilder(handshaker).base(head)

    val serverSession = handshaker.handshake()
    head.reads.dequeue().success(bits.getPrefaceBuffer())

    // Consume all the inbound data which consists of the server settings
    head.consumeOutboundByteBuf()

    // Send in a settings frame
    val frame = FrameSerializer.mkSettingsFrame(Seq.empty)
    head.reads.dequeue().success(frame)

    ProtocolFrameDecoder.decode(head.consumeOutboundByteBuf()) match {
      case ProtocolFrame.Settings(None) => () // should have sent an ack
      case _ => fail("Unexpected decode result found")
    }

    serverSession.value match {
      case Some(Success(_)) => ()
      case _ => fail("Unexpected handshake result found")
    }
  }

  test("A ServerPriorKnowledgeHandshaker should sends settings") {
    val head = new MockByteBufferHeadStage
    val settings = Http2Settings.default
    val tail = makeHandshaker(settings)
    LeafBuilder(tail).base(head)

    tail.handshake()
    head.reads.dequeue().success(bits.getPrefaceBuffer())

    ProtocolFrameDecoder.decode(head.consumeOutboundByteBuf()) match {
      case ProtocolFrame.Settings(Some(_)) => ()
      case _ => fail("Unexpected decode result found")
    }
  }

  test(
    "A ServerPriorKnowledgeHandshaker should sends a GOAWAY(PROTOCOL_ERROR) " +
      "if settings frame that exceeds the local MAX_FRAME_SIZE") {
    val localSettings = Http2Settings.default.copy(maxFrameSize = 0)
    val head = new MockByteBufferHeadStage
    val handshaker = makeHandshaker(localSettings)
    LeafBuilder(handshaker).base(head)

    val serverSession = handshaker.handshake()
    head.reads.dequeue().success(bits.getPrefaceBuffer())

    // Consume all the inbound data which is the prelude and the local settings
    head.consumeOutboundByteBuf()

    // We synthesize a SETTINGS frame header that has a size larger than 0
    val frame = BufferTools.allocate(bits.HeaderSize)
    // first 3 bytes are length, next is type, then
    frame.putInt(0x01 << 8 | bits.FrameTypes.SETTINGS)
    frame.put(0x00.toByte) // 1 byte flags
    frame.putInt(0x00) // 4 bytes stream id
    frame.flip()
    assert(frame.remaining == 9)

    head.reads.dequeue().success(frame)

    ProtocolFrameDecoder.decode(head.consumeOutboundByteBuf()) match {
      case ProtocolFrame.GoAway(0, cause) =>
        assertEquals(cause.code, Http2Exception.FRAME_SIZE_ERROR.code)
      case _ => fail("Unexpected decode result found")
    }

    serverSession.value match {
      case Some(Failure(ex: Http2Exception)) =>
        assertEquals(ex.code, Http2Exception.FRAME_SIZE_ERROR.code)
      case _ =>
        fail("Unexpected handshake result found")
    }

    assert(head.disconnected)
  }

  test(
    "A ServerPriorKnowledgeHandshaker should sends a GOAWAY(PROTOCOL_ERROR) " +
      "if the first frame isn't a settings frame") {
    val localSettings = Http2Settings.default
    val head = new MockByteBufferHeadStage
    val handshaker = makeHandshaker(localSettings)
    LeafBuilder(handshaker).base(head)

    val serverSession = handshaker.handshake()
    head.reads.dequeue().success(bits.getPrefaceBuffer())

    // Consume all the inbound data which is the local settings
    head.consumeOutboundByteBuf()

    // Send in a non-settings frame
    val frame = FrameSerializer.mkRstStreamFrame(1, 1)
    head.reads.dequeue().success(frame)

    ProtocolFrameDecoder.decode(head.consumeOutboundByteBuf()) match {
      case ProtocolFrame.GoAway(0, cause) =>
        assertEquals(cause.code, Http2Exception.PROTOCOL_ERROR.code)
      case _ =>
        fail("Unexpected decode result found")
    }

    serverSession.value match {
      case Some(Failure(ex: Http2Exception)) =>
        assertEquals(ex.code, Http2Exception.PROTOCOL_ERROR.code)
      case _ =>
        fail("Unexpected handshake result found")
    }

    assert(head.disconnected)
  }
}
