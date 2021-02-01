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

import org.http4s.blaze.http.http2.client.ClientPriorKnowledgeHandshaker
import org.http4s.blaze.http.http2.mocks.MockByteBufferHeadStage
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.http4s.blaze.util.Execution
import org.specs2.mutable.Specification

import scala.util.{Failure, Success}

class ClientPriorKnowledgeHandshakerSpec extends Specification {
  private def makeHandshaker(
      localSettings: ImmutableHttp2Settings): ClientPriorKnowledgeHandshaker = {
    val flowStrategy = new DefaultFlowStrategy(localSettings)
    new ClientPriorKnowledgeHandshaker(
      localSettings = localSettings,
      flowStrategy = flowStrategy,
      executor = Execution.trampoline)
  }

  "ClientPriorKnowledgeHandshaker" >> {
    "can perform a handshake" in {
      val localSettings = Http2Settings.default
      val head = new MockByteBufferHeadStage
      val handshaker = makeHandshaker(localSettings)
      LeafBuilder(handshaker).base(head)
      head.sendInboundCommand(Command.Connected)

      // Consume all the inbound data which may be a prelude and the local settings
      head.consumeOutboundByteBuf()
      head.consumeOutboundByteBuf()

      // Send in a settings frame
      val frame = FrameSerializer.mkSettingsFrame(Seq.empty)
      head.reads.dequeue().success(frame)

      ProtocolFrameDecoder.decode(head.consumeOutboundByteBuf()) must beLike {
        case ProtocolFrame.Settings(None) => ok // should have sent an ack
      }

      handshaker.clientSession.value must beLike { case Some(Success(_)) =>
        ok
      }
    }

    "Sends settings" in {
      val head = new MockByteBufferHeadStage
      val settings = Http2Settings.default
      val tail = makeHandshaker(settings)
      LeafBuilder(tail).base(head)
      head.sendInboundCommand(Command.Connected)

      head.consumeOutboundByteBuf() must_== bits.getPrefaceBuffer()
      ProtocolFrameDecoder.decode(head.consumeOutboundByteBuf()) must beLike {
        case ProtocolFrame.Settings(Some(_)) => ok
      }
    }

    "Sends a GOAWAY(PROTOCOL_ERROR) if settings frame that exceeds the local MAX_FRAME_SIZE" in {
      val localSettings = Http2Settings.default.copy(maxFrameSize = 0)
      val head = new MockByteBufferHeadStage
      val handshaker = makeHandshaker(localSettings)
      LeafBuilder(handshaker).base(head)
      head.sendInboundCommand(Command.Connected)

      // Consume all the inbound data which may be a prelude and the local settings
      head.consumeOutboundByteBuf()
      head.consumeOutboundByteBuf()

      // Send in a settings frame that is guarenteed to break the limit
      val frame = FrameSerializer.mkSettingsFrame(Seq(Http2Settings.HEADER_TABLE_SIZE(3)))
      head.reads.dequeue().success(frame)

      ProtocolFrameDecoder.decode(head.consumeOutboundByteBuf()) must beLike {
        case ProtocolFrame.GoAway(0, cause) =>
          cause.code must_== Http2Exception.FRAME_SIZE_ERROR.code
      }

      handshaker.clientSession.value must beLike { case Some(Failure(ex: Http2Exception)) =>
        ex.code must_== Http2Exception.FRAME_SIZE_ERROR.code
      }

      head.disconnected must beTrue
    }

    "Sends a GOAWAY(PROTOCOL_ERROR) if the first frame isn't a settings frame" in {
      val localSettings = Http2Settings.default
      val head = new MockByteBufferHeadStage
      val handshaker = makeHandshaker(localSettings)
      LeafBuilder(handshaker).base(head)
      head.sendInboundCommand(Command.Connected)

      // Consume all the inbound data which may be a prelude and the local settings
      head.consumeOutboundByteBuf()
      head.consumeOutboundByteBuf()

      // Send in a non-settings frame
      val frame = FrameSerializer.mkRstStreamFrame(1, 1)
      head.reads.dequeue().success(frame)

      ProtocolFrameDecoder.decode(head.consumeOutboundByteBuf()) must beLike {
        case ProtocolFrame.GoAway(0, cause) => cause.code must_== Http2Exception.PROTOCOL_ERROR.code
      }

      handshaker.clientSession.value must beLike { case Some(Failure(ex: Http2Exception)) =>
        ex.code must_== Http2Exception.PROTOCOL_ERROR.code
      }

      head.disconnected must beTrue
    }
  }
}
