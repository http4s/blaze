package org.http4s.blaze.http.http2

import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

class StreamFlowWindowSpec extends Specification with Mockito {
  "StreamFlowWindow" >> {
    "outboundWindow gives minimum of session and stream outbound windows" >> {
      val tools = new Http2MockTools(true /*isClient*/)
      val initialSessionWindow = tools.flowControl.sessionOutboundWindow

      initialSessionWindow must beGreaterThan(10) // sanity check
      val window = mock[StreamFlowWindow]
      window.streamOutboundWindow returns 10
      window.sessionFlowControl returns tools.flowControl
      window.outboundWindow must_== 10

      // deplete the session window and make sure we get a 0 out
      tools.flowControl.newStreamFlowWindow(1).outboundRequest(initialSessionWindow) must_== initialSessionWindow
      window.outboundWindow must_== 0
    }

    "outboundWindowAvailable" >> {
      val tools = new Http2MockTools(true /*isClient*/)
      val initialSessionWindow = tools.flowControl.sessionOutboundWindow

      tools.flowControl.sessionOutboundWindow must beGreaterThan(10) // sanity check
      val window = mock[StreamFlowWindow]
      window.streamOutboundWindow returns 10
      window.sessionFlowControl returns tools.flowControl
      window.outboundWindowAvailable must beTrue // neither depleted

      window.streamOutboundWindow returns 0
      window.outboundWindowAvailable must beFalse // stream depleted

      // deplete the session window and make sure we get a false
      tools.flowControl.newStreamFlowWindow(1).outboundRequest(initialSessionWindow) must_== initialSessionWindow
      window.outboundWindowAvailable must beFalse // both depleted

      window.streamOutboundWindow returns 10
      window.outboundWindowAvailable must beFalse // session depleted
    }
  }

}
