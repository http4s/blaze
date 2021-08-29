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
import org.http4s.blaze.testkit.BlazeTestSuite

class StreamFlowWindowSuite extends BlazeTestSuite {
  private class Tools extends MockTools(isClient = true) {
    override lazy val sessionFlowControl: SessionFlowControl =
      new ObservingSessionFlowControl(this)
  }

  test("A StreamFlowWindow outboundWindow gives minimum of session and stream outbound windows") {
    val tools = new Tools
    val initialSessionWindow = tools.sessionFlowControl.sessionOutboundWindow

    assert(initialSessionWindow > 10) // sanity check

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

    assertEquals(window.outboundWindow, 10)

    // deplete the session window and make sure we get a 0 out
    assertEquals(
      tools.sessionFlowControl
        .newStreamFlowWindow(1)
        .outboundRequest(initialSessionWindow),
      initialSessionWindow)
    assertEquals(window.outboundWindow, 0)
  }

  test("A StreamFlowWindow outboundWindowAvailable") {
    val tools = new Tools
    val initialSessionWindow = tools.sessionFlowControl.sessionOutboundWindow

    assert(tools.sessionFlowControl.sessionOutboundWindow > 10) // sanity check

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

    assert(window1.outboundWindowAvailable) // neither depleted

    val window2 = window(0)
    assertEquals(window2.outboundWindowAvailable, false) // stream depleted

    // deplete the session window and make sure we get a false
    assertEquals(
      tools.sessionFlowControl
        .newStreamFlowWindow(1)
        .outboundRequest(initialSessionWindow),
      initialSessionWindow)
    assertEquals(window2.outboundWindowAvailable, false) // both depleted

    assertEquals(window1.outboundWindowAvailable, false) // session depleted
  }
}
