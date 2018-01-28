package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.mocks.MockHeadStage
import org.http4s.blaze.http.http2.server.ServerPriorKnowledgeHandshaker
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

import scala.util.{Failure, Success}

class ServerPriorKnowledgeHandshakerSpec extends Specification {

  private def makeHandshaker(localSettings: ImmutableHttp2Settings): ServerPriorKnowledgeHandshaker = {
    val flowStrategy = new DefaultFlowStrategy(localSettings)
    new ServerPriorKnowledgeHandshaker(
      localSettings = localSettings,
      flowStrategy = flowStrategy,
      nodeBuilder = _ => ???
    )
  }

  "ServerPriorKnowledgeHandshaker">> {
    "can perform a handshake" in {
      val localSettings = Http2Settings.default
      val head = new MockHeadStage
      val handshaker = makeHandshaker(localSettings)
      LeafBuilder(handshaker).base(head)

      val serverSession = handshaker.handshake()
      head.reads.dequeue().success(bits.getPrefaceBuffer())

      // Consume all the inbound data which consists of the server settings
      head.consumeOutboundData()

      // Send in a settings frame
      val frame = FrameSerializer.mkSettingsFrame(Seq.empty)
      head.reads.dequeue().success(frame)

      ProtocolFrameDecoder.decode(head.consumeOutboundData()) must beLike {
        case ProtocolFrame.Settings(None) => ok // should have sent an ack
      }

      serverSession.value must beLike {
        case Some(Success(_)) => ok
      }
    }

    "Sends settings" in {
      val head = new MockHeadStage
      val settings = Http2Settings.default
      val tail = makeHandshaker(settings)
      LeafBuilder(tail).base(head)

      tail.handshake()
      head.reads.dequeue().success(bits.getPrefaceBuffer())

      ProtocolFrameDecoder.decode(head.consumeOutboundData()) must beLike {
        case ProtocolFrame.Settings(Some(_)) => ok
      }
    }

    "Sends a GOAWAY(PROTOCOL_ERROR) if settings frame that exceeds the local MAX_FRAME_SIZE" in {
      val localSettings = Http2Settings.default.copy(maxFrameSize = 0)
      val head = new MockHeadStage
      val handshaker = makeHandshaker(localSettings)
      LeafBuilder(handshaker).base(head)

      val serverSession = handshaker.handshake()
      head.reads.dequeue().success(bits.getPrefaceBuffer())

      // Consume all the inbound data which is the prelude and the local settings
      head.consumeOutboundData()

      // We synthesize a SETTINGS frame header that has a size larger than 0
      val frame = BufferTools.allocate(bits.HeaderSize)
      // first 3 bytes are length, next is type, then
      frame.putInt(0x01 << 8 | bits.FrameTypes.SETTINGS)
      frame.put(0x00.toByte) // 1 byte flags
      frame.putInt(0x00) // 4 bytes stream id
      frame.flip()
      assert(frame.remaining == 9)

      head.reads.dequeue().success(frame)

      ProtocolFrameDecoder.decode(head.consumeOutboundData()) must beLike {
        case ProtocolFrame.GoAway(0, cause) => cause.code must_== Http2Exception.FRAME_SIZE_ERROR.code
      }

      serverSession.value must beLike {
        case Some(Failure(ex: Http2Exception)) => ex.code must_== Http2Exception.FRAME_SIZE_ERROR.code
      }

      head.disconnected must beTrue
    }

    "Sends a GOAWAY(PROTOCOL_ERROR) if the first frame isn't a settings frame" in {
      val localSettings = Http2Settings.default
      val head = new MockHeadStage
      val handshaker = makeHandshaker(localSettings)
      LeafBuilder(handshaker).base(head)

      val serverSession = handshaker.handshake()
      head.reads.dequeue().success(bits.getPrefaceBuffer())

      // Consume all the inbound data which is the local settings
      head.consumeOutboundData()

      // Send in a non-settings frame
      val frame = FrameSerializer.mkRstStreamFrame(1, 1)
      head.reads.dequeue().success(frame)

      ProtocolFrameDecoder.decode(head.consumeOutboundData()) must beLike {
        case ProtocolFrame.GoAway(0, cause) => cause.code must_== Http2Exception.PROTOCOL_ERROR.code
      }

      serverSession.value must beLike {
        case Some(Failure(ex: Http2Exception)) => ex.code must_== Http2Exception.PROTOCOL_ERROR.code
      }

      head.disconnected must beTrue
    }
  }
}
