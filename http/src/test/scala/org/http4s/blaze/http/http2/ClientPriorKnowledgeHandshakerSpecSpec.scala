package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.client.ClientPriorKnowledgeHandshaker
import org.http4s.blaze.http.http2.mocks.MockHeadStage
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.http4s.blaze.util.Execution
import org.specs2.mutable.Specification

import scala.util.{Failure, Success}

class ClientPriorKnowledgeHandshakerSpec extends Specification {

  private def makeHandshaker(localSettings: ImmutableHttp2Settings): ClientPriorKnowledgeHandshaker = {
    val flowStrategy = new DefaultFlowStrategy(localSettings)
    new ClientPriorKnowledgeHandshaker(
      localSettings = localSettings,
      flowStrategy = flowStrategy,
      executor = Execution.trampoline)
  }

  "ClientPriorKnowledgeHandshaker">> {
    "can perform a handshake" in {
      val localSettings = Http2Settings.default
      val head = new MockHeadStage
      val handshaker = makeHandshaker(localSettings)
      LeafBuilder(handshaker).base(head)
      head.sendInboundCommand(Command.Connected)

      // Consume all the inbound data which may be a prelude and the local settings
      head.consumeOutboundData()
      head.consumeOutboundData()

      // Send in a settings frame
      val frame = FrameSerializer.mkSettingsFrame(Seq.empty)
      head.reads.dequeue().success(frame)

      ProtocolFrameDecoder.decode(head.consumeOutboundData()) must beLike {
        case ProtocolFrame.Settings(None) => ok // should have sent an ack
      }

      handshaker.clientSession.value must beLike {
        case Some(Success(_)) => ok
      }
    }

    "Sends settings" in {
      val head = new MockHeadStage
      val settings = Http2Settings.default
      val tail = makeHandshaker(settings)
      LeafBuilder(tail).base(head)
      head.sendInboundCommand(Command.Connected)

      head.consumeOutboundData() must_== bits.getPrefaceBuffer()
      ProtocolFrameDecoder.decode(head.consumeOutboundData()) must beLike {
        case ProtocolFrame.Settings(Some(_)) => ok
      }
    }

    "Sends a GOAWAY(PROTOCOL_ERROR) if settings frame that exceeds the local MAX_FRAME_SIZE" in {
      val localSettings = Http2Settings.default.copy(maxFrameSize = 0)
      val head = new MockHeadStage
      val handshaker = makeHandshaker(localSettings)
      LeafBuilder(handshaker).base(head)
      head.sendInboundCommand(Command.Connected)

      // Consume all the inbound data which may be a prelude and the local settings
      head.consumeOutboundData()
      head.consumeOutboundData()

      // Send in a settings frame that is guarenteed to break the limit
      val frame = FrameSerializer.mkSettingsFrame(Seq(Http2Settings.HEADER_TABLE_SIZE(3)))
      head.reads.dequeue().success(frame)

      ProtocolFrameDecoder.decode(head.consumeOutboundData()) must beLike {
        case ProtocolFrame.GoAway(0, cause) => cause.code must_== Http2Exception.FRAME_SIZE_ERROR.code
      }

      handshaker.clientSession.value must beLike {
        case Some(Failure(ex: Http2Exception)) => ex.code must_== Http2Exception.FRAME_SIZE_ERROR.code
      }
    }

    "Sends a GOAWAY(PROTOCOL_ERROR) if the first frame isn't a settings frame" in {
      val localSettings = Http2Settings.default
      val head = new MockHeadStage
      val handshaker = makeHandshaker(localSettings)
      LeafBuilder(handshaker).base(head)
      head.sendInboundCommand(Command.Connected)

      // Consume all the inbound data which may be a prelude and the local settings
      head.consumeOutboundData()
      head.consumeOutboundData()

      // Send in a non-settings frame
      val frame = FrameSerializer.mkRstStreamFrame(1, 1)
      head.reads.dequeue().success(frame)

      ProtocolFrameDecoder.decode(head.consumeOutboundData()) must beLike {
        case ProtocolFrame.GoAway(0, cause) => cause.code must_== Http2Exception.PROTOCOL_ERROR.code
      }

      handshaker.clientSession.value must beLike {
        case Some(Failure(ex: Http2Exception)) => ex.code must_== Http2Exception.PROTOCOL_ERROR.code
      }
    }
  }
}
