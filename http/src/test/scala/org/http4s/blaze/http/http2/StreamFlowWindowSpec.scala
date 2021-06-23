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

import org.http4s.blaze.http.http2.mocks.{MockTools, ObservingSessionFlowControl}
import org.specs2.mutable.Specification

class StreamFlowWindowSpec extends Specification {
  private class Tools extends MockTools(isClient = true) {
    override lazy val sessionFlowControl: SessionFlowControl =
      new ObservingSessionFlowControl(this)
  }

  "StreamFlowWindow" >> {
    "outboundWindow gives minimum of session and stream outbound windows" >> {
      val tools = new Tools
      val initialSessionWindow = tools.sessionFlowControl.sessionOutboundWindow

      initialSessionWindow must beGreaterThan(10) // sanity check

      val window = new StreamFlowWindow {
        def sessionFlowControl: SessionFlowControl = tools.sessionFlowControl
        def streamId: Int = ???
        def streamUnconsumedBytes: Int = ???
        def streamOutboundWindow: Int = 10
        def remoteSettingsInitialWindowChange(delta: Int): Option[Http2Exception] = ???
        def streamOutboundAcked(count: Int): Option[Http2Exception] = ???
        def outboundRequest(request: Int): Int = ???
        def streamInboundWindow: Int = ???
        def inboundObserved(count: Int): Boolean = ???
        def inboundConsumed(count: Int): Unit = ???
        def streamInboundAcked(count: Int): Unit = ???
      }

      window.outboundWindow must_== 10

      // deplete the session window and make sure we get a 0 out
      tools.sessionFlowControl
        .newStreamFlowWindow(1)
        .outboundRequest(initialSessionWindow) must_== initialSessionWindow
      window.outboundWindow must_== 0
    }

    "outboundWindowAvailable" >> {
      val tools = new Tools
      val initialSessionWindow = tools.sessionFlowControl.sessionOutboundWindow

      tools.sessionFlowControl.sessionOutboundWindow must beGreaterThan(10) // sanity check

      def window(streamOutboundWindowMock: Int) = new StreamFlowWindow {
        def sessionFlowControl: SessionFlowControl = tools.sessionFlowControl
        def streamId: Int = ???
        def streamUnconsumedBytes: Int = ???
        def streamOutboundWindow: Int = streamOutboundWindowMock
        def remoteSettingsInitialWindowChange(delta: Int): Option[Http2Exception] = ???
        def streamOutboundAcked(count: Int): Option[Http2Exception] = ???
        def outboundRequest(request: Int): Int = ???
        def streamInboundWindow: Int = ???
        def inboundObserved(count: Int): Boolean = ???
        def inboundConsumed(count: Int): Unit = ???
        def streamInboundAcked(count: Int): Unit = ???
      }

      val window1 = window(10)

      window1.outboundWindowAvailable must beTrue // neither depleted

      val window2 = window(0)
      window2.outboundWindowAvailable must beFalse // stream depleted

      // deplete the session window and make sure we get a false
      tools.sessionFlowControl
        .newStreamFlowWindow(1)
        .outboundRequest(initialSessionWindow) must_== initialSessionWindow
      window2.outboundWindowAvailable must beFalse // both depleted

      window1.outboundWindowAvailable must beFalse // session depleted
    }
  }
}
