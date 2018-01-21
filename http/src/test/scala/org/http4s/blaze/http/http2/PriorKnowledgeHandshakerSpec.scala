package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.client.ClientPriorKnowledgeHandshaker
import org.http4s.blaze.http.http2.mocks.MockHeadStage
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.http4s.blaze.util.Execution
import org.specs2.mutable.Specification

abstract class PriorKnowledgeHandshakerSpec extends Specification {

  protected def makeHandshaker(localSettings: ImmutableHttp2Settings): PriorKnowledgeHandshaker[_]

  "generic" >> {
    "Sends settings" in {
      val head = new MockHeadStage
      val settings = Http2Settings.default
      val tail = makeHandshaker(settings)
      LeafBuilder(tail).base(head)
      head.sendInboundCommand(Command.Connected)




      ko
    }

    "Can perform a successful handshake" in {
      ko
    }

    "Rejects settings frame that exceeds the local MAX_FRAME_SIZE" in {
      ko
    }

    "Sends a GOAWAY if the first frame isn't a settings frame" in {
      ko
    }
  }
}

class ClientPriorKnowledgeHandshakerSpec extends PriorKnowledgeHandshakerSpec {
  override protected def makeHandshaker(localSettings: ImmutableHttp2Settings): PriorKnowledgeHandshaker[_] = {
    val flowStrategy = new DefaultFlowStrategy(localSettings)
    new ClientPriorKnowledgeHandshaker(
      localSettings = localSettings,
      flowStrategy = flowStrategy,
      executor = Execution.trampoline)
  }

  "client">> {
    "Sends settings" in {
      val head = new MockHeadStage
      val settings = Http2Settings.default
      val tail = makeHandshaker(settings)
      LeafBuilder(tail).base(head)
      head.sendInboundCommand(Command.Connected)

      val data = head.consumeOutboundData()
      ko
    }
  }
}
