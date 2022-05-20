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

import org.http4s.blaze.http.http2.Http2Settings.DefaultSettings
import org.http4s.blaze.http.http2.mocks.MockTools
import org.http4s.blaze.testkit.BlazeTestSuite

class SessionFlowControlSuite extends BlazeTestSuite {
  private class TestSessionFlowControl(session: SessionCore)
      extends SessionFlowControlImpl(session, null) {
    var sessionConsumed: Int = 0

    var streamThatConsumed: StreamFlowWindow = null
    var streamConsumed: Int = 0

    override protected def onSessonBytesConsumed(consumed: Int): Unit =
      this.sessionConsumed = consumed

    override protected def onStreamBytesConsumed(stream: StreamFlowWindow, consumed: Int): Unit = {
      streamThatConsumed = stream
      streamConsumed = consumed
    }
  }

  private def flowControl(): TestSessionFlowControl = {
    val settings = Http2Settings.default
    flowControl(settings, settings)
  }

  private def flowControl(local: Http2Settings, remote: Http2Settings): TestSessionFlowControl = {
    val core = new MockTools(true /* doesn't matter */ ) {
      override lazy val remoteSettings: MutableHttp2Settings = MutableHttp2Settings(remote)
      override lazy val localSettings: MutableHttp2Settings = MutableHttp2Settings(local)
    }
    new TestSessionFlowControl(core)
  }

  test(
    "A SessionFlowControl session inbound window should start with the http2 default flow windows") {
    val flow = flowControl()
    assertEquals(flow.sessionInboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE)
    assertEquals(flow.sessionOutboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE)
  }

  test("A SessionFlowControl session inbound window should bytes consumed") {
    val flow = flowControl()
    assertEquals(flow.sessionUnconsumedBytes, 0)
    assert(flow.sessionInboundObserved(10))
    assertEquals(flow.sessionUnconsumedBytes, 10)

    assertEquals(flow.sessionConsumed, 0)
    flow.sessionInboundConsumed(1)

    assertEquals(flow.sessionConsumed, 1)
    assertEquals(flow.sessionUnconsumedBytes, 9)
  }

  test("A SessionFlowControl zero session inbound withdrawals don't deplete the window") {
    val flow = flowControl()
    assert(flow.sessionInboundObserved(0))
    assertEquals(flow.sessionInboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE)
  }

  test("A SessionFlowControl session inbound withdrawals less than the window are successful") {
    val flow = flowControl()
    assert(flow.sessionInboundObserved(1))
    assertEquals(flow.sessionInboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE - 1)
  }

  test(
    "A SessionFlowControl session inbound withdrawals greater than " +
      "the window result in false and don't deplete the window") {
    val flow = flowControl()
    assertEquals(flow.sessionInboundObserved(DefaultSettings.INITIAL_WINDOW_SIZE + 1), false)
    assertEquals(flow.sessionInboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE)
  }

  test("A SessionFlowControl session inbound withdrawals equal than the window are successful") {
    val flow = flowControl()
    assert(flow.sessionInboundObserved(DefaultSettings.INITIAL_WINDOW_SIZE))
    assertEquals(flow.sessionInboundWindow, 0)
  }

  test("A SessionFlowControl session inbound deposits update the window") {
    val flow = flowControl()
    flow.sessionInboundAcked(1)
    assertEquals(flow.sessionInboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE + 1)
  }

  test("A SessionFlowControl session inbound deposits update the window to Int.MaxValue") {
    val flow = flowControl()
    flow.sessionInboundAcked(Int.MaxValue - flow.sessionInboundWindow)
    assertEquals(flow.sessionInboundWindow, Int.MaxValue)
  }

  test("A SessionFlowControl session outbound deposits update the window") {
    val flow = flowControl()
    assert(flow.sessionOutboundAcked(1).isEmpty)
    assertEquals(flow.sessionOutboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE + 1)
  }

  test("A SessionFlowControl session outbound deposits update the window to Int.MaxValue") {
    val flow = flowControl()
    assert(flow.sessionOutboundAcked(Int.MaxValue - flow.sessionOutboundWindow).isEmpty)
    assertEquals(flow.sessionOutboundWindow, Int.MaxValue)
  }

  // https://tools.ietf.org/html/rfc7540#section-6.9
  test(
    "A SessionFlowControl session outbound deposits of 0 throw Http2Exception with flag FLOW_CONTROL") {
    val flow = flowControl()
    flow.sessionOutboundAcked(0) match {
      case Some(Http2SessionException(code, _)) =>
        assertEquals(code, Http2Exception.PROTOCOL_ERROR.code)

      case _ =>
        fail("Unexpected sessionOutboundAcked result found")
    }
  }

  // https://tools.ietf.org/html/rfc7540#section-6.9.1
  test(
    "A SessionFlowControl session outbound deposits that overflow " +
      "the window throw Http2Exception with flag FLOW_CONTROL") {
    val flow = flowControl()
    val overflowBy1 = Int.MaxValue - flow.sessionOutboundWindow + 1
    flow.sessionOutboundAcked(overflowBy1) match {
      case Some(Http2SessionException(code, _)) =>
        assertEquals(code, Http2Exception.FLOW_CONTROL_ERROR.code)
      case _ =>
        fail("Unexpected sessionOutboundAcked result found")
    }
  }

  // //////////////// Streams ////////////////////////////

  test(
    "A SessionFlowControl.StreamFlowWindow inbound window should start with the config initial flow windows") {
    val inbound = Http2Settings.default.copy(initialWindowSize = 2)
    val outbound = Http2Settings.default.copy(initialWindowSize = 1)
    val flow = flowControl(inbound, outbound).newStreamFlowWindow(1)

    assertEquals(flow.streamInboundWindow, 2)
    assertEquals(flow.streamOutboundWindow, 1)
  }

  test("A SessionFlowControl.StreamFlowWindow inbound window should bytes consumed") {
    val session = flowControl()
    val flow = session.newStreamFlowWindow(1)

    assertEquals(session.sessionUnconsumedBytes, 0)
    assertEquals(flow.streamUnconsumedBytes, 0)

    assert(flow.inboundObserved(10))

    assertEquals(session.sessionUnconsumedBytes, 10)
    assertEquals(flow.streamUnconsumedBytes, 10)

    assertEquals(session.sessionConsumed, 0)
    assertEquals(session.streamConsumed, 0)
    flow.inboundConsumed(1)

    assert(session.streamThatConsumed eq flow)
    assertEquals(session.sessionConsumed, 1)
    assertEquals(session.streamConsumed, 1)

    assertEquals(session.sessionUnconsumedBytes, 9)
    assertEquals(flow.streamUnconsumedBytes, 9)
  }

  test("A SessionFlowControl.StreamFlowWindow zero inbound withdrawals don't deplete the window") {
    val flow = flowControl().newStreamFlowWindow(1)
    assert(flow.inboundObserved(0))
    assertEquals(flow.streamInboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE)
  }

  test(
    "A SessionFlowControl.StreamFlowWindow inbound withdrawals less than the window are successful") {
    val session = flowControl()
    val flow = session.newStreamFlowWindow(1)
    assert(flow.inboundObserved(1))

    assertEquals(flow.streamInboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE - 1)
    assertEquals(session.sessionInboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE - 1)
  }

  test(
    "A SessionFlowControl.StreamFlowWindow inbound withdrawals greater than " +
      "the window result in false and don't deplete the window") {
    val session = flowControl()
    val flow = session.newStreamFlowWindow(1)
    assertEquals(flow.inboundObserved(DefaultSettings.INITIAL_WINDOW_SIZE + 1), false)

    assertEquals(flow.streamInboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE)
    assertEquals(session.sessionInboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE)
  }

  test(
    "A SessionFlowControl.StreamFlowWindow inbound withdrawals equal than the window are successful") {
    val session = flowControl()
    val flow = session.newStreamFlowWindow(1)

    assert(flow.inboundObserved(DefaultSettings.INITIAL_WINDOW_SIZE))
    assertEquals(flow.streamInboundWindow, 0)
    assertEquals(session.sessionInboundWindow, 0)
  }

  test("A SessionFlowControl.StreamFlowWindow inbound deposits update the window") {
    val session = flowControl()
    val flow = session.newStreamFlowWindow(1)

    flow.streamInboundAcked(1)
    assertEquals(flow.streamInboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE + 1)
    assertEquals(session.sessionInboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE)
  }

  test("A SessionFlowControl.StreamFlowWindow inbound deposits update the window to Int.MaxValue") {
    val session = flowControl()
    val flow = session.newStreamFlowWindow(1)

    flow.streamInboundAcked(Int.MaxValue - flow.streamInboundWindow)
    assertEquals(session.sessionInboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE)
  }

  test("A SessionFlowControlStreamFlowWindow deposits update the window") {
    val session = flowControl()
    val flow = session.newStreamFlowWindow(1)

    assert(flow.streamOutboundAcked(1).isEmpty)

    assertEquals(flow.streamOutboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE + 1)
    assertEquals(session.sessionOutboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE)
  }

  test("A SessionFlowControlStreamFlowWindow outbound deposits update the window to Int.MaxValue") {
    val session = flowControl()
    val flow = session.newStreamFlowWindow(1)

    assert(flow.streamOutboundAcked(Int.MaxValue - flow.streamOutboundWindow).isEmpty)

    assertEquals(flow.streamOutboundWindow, Int.MaxValue)
    assertEquals(session.sessionOutboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE)
  }

  // https://tools.ietf.org/html/rfc7540#section-6.9
  test(
    "A SessionFlowControlStreamFlowWindow outbound deposits of 0 throw Http2Exception with flag FLOW_CONTROL") {
    val flow = flowControl().newStreamFlowWindow(1)

    flow.streamOutboundAcked(0) match {
      case Some(Http2SessionException(code, _)) =>
        assertEquals(code, Http2Exception.PROTOCOL_ERROR.code)
      case _ =>
        fail("Unexpected streamOutboundAcked result found")
    }
  }

  // https://tools.ietf.org/html/rfc7540#section-6.9.1
  test(
    "A SessionFlowControlStreamFlowWindow outbound deposits that overflow " +
      "the window throw Http2Exception with flag FLOW_CONTROL") {
    val flow = flowControl().newStreamFlowWindow(1)

    val overflowBy1 = Int.MaxValue - flow.streamOutboundWindow + 1
    flow.streamOutboundAcked(overflowBy1) match {
      case Some(Http2StreamException(streamId, code, _)) =>
        assertEquals(streamId, flow.streamId)
        assertEquals(code, Http2Exception.FLOW_CONTROL_ERROR.code)

      case _ =>
        fail("Unexpected streamOutboundAcked result found")
    }
  }

  test("A SessionFlowControlStreamFlowWindow outbound withdrawal of 0 don't effect the windows") {
    val session = flowControl()
    val flow = session.newStreamFlowWindow(1)

    assertEquals(flow.outboundRequest(0), 0)
    assertEquals(flow.streamOutboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE)
    assertEquals(session.sessionOutboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE)
  }

  test("A SessionFlowControlStreamFlowWindow outbound withdrawals are accounted for") {
    val session = flowControl()
    val flow = session.newStreamFlowWindow(1)

    assertEquals(
      flow.outboundRequest(DefaultSettings.INITIAL_WINDOW_SIZE),
      DefaultSettings.INITIAL_WINDOW_SIZE)
    assertEquals(flow.streamOutboundWindow, 0)
    assertEquals(session.sessionOutboundWindow, 0)
  }

  test("A SessionFlowControlStreamFlowWindow outbound withdrawals that exceed the window") {
    val session = flowControl()
    val flow = session.newStreamFlowWindow(1)

    assertEquals(
      flow.outboundRequest(DefaultSettings.INITIAL_WINDOW_SIZE + 1),
      DefaultSettings.INITIAL_WINDOW_SIZE)
    assertEquals(flow.streamOutboundWindow, 0)
    assertEquals(session.sessionOutboundWindow, 0)
  }

  test(
    "A SessionFlowControlStreamFlowWindow outbound withdrawals that exceed " +
      "the window consume the max from stream or session") {
    val config = Http2Settings.default.copy(initialWindowSize = 1)
    val session = flowControl(config, config)
    val flow = session.newStreamFlowWindow(1)

    assertEquals(flow.outboundRequest(10), 1)
    assertEquals(flow.streamOutboundWindow, 0)
    assertEquals(session.sessionOutboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE - 1)
  }

  test("A SessionFlowControlStreamFlowWindow outbound withdrawals from multiple streams") {
    val session = flowControl()
    val flow1 = session.newStreamFlowWindow(1)
    val flow2 = session.newStreamFlowWindow(2)

    assertEquals(flow1.outboundRequest(10), 10)
    assertEquals(flow1.streamOutboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE - 10)
    assertEquals(flow2.streamOutboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE)
    assertEquals(session.sessionOutboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE - 10)

    assertEquals(flow2.outboundRequest(20), 20)
    assertEquals(flow2.streamOutboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE - 20)
    assertEquals(flow1.streamOutboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE - 10)
    assertEquals(session.sessionOutboundWindow, DefaultSettings.INITIAL_WINDOW_SIZE - 30)
  }
}
