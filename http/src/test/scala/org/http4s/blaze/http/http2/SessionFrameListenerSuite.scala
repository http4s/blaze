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

import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.Headers
import org.http4s.blaze.http.http2.Http2Exception._
import org.http4s.blaze.http.http2.mocks.MockStreamManager
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.pipeline.stages.BasicTail
import org.http4s.blaze.testkit.BlazeTestSuite
import org.http4s.blaze.util.BufferTools

import scala.util.Success

class SessionFrameListenerSuite extends BlazeTestSuite {
  private class MockTools(isClient: Boolean) extends mocks.MockTools(isClient) {
    lazy val headerDecoder: HeaderDecoder =
      new HeaderDecoder(
        localSettings.maxHeaderListSize,
        true, // discard overflow headers
        localSettings.headerTableSize)

    lazy val newInboundStream: Option[Int => LeafBuilder[StreamFrame]] = None

    lazy val frameListener: SessionFrameListener =
      new SessionFrameListener(this, isClient, headerDecoder)

    override lazy val streamManager: StreamManager = new StreamManagerImpl(this, newInboundStream)
  }

  val hs = Seq("foo" -> "bar")

  test("A SessionFrameListener should on HEADERS frame use an existing stream") {
    val tools: MockTools = new MockTools(isClient = true)
    val os = tools.streamManager.newOutboundStream()

    // initialize the stream
    os.writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty))

    tools.frameListener.onCompleteHeadersFrame(
      streamId = os.streamId,
      priority = Priority.NoPriority,
      endStream = false,
      headers = hs)

    os.readRequest(1).value match {
      case Some(Success(HeadersFrame(Priority.NoPriority, false, hss))) =>
        assertEquals(hs, hss.toList)
      case _ =>
        fail("Unexpected readRequest result found")
    }
  }

  test(
    "A SessionFrameListener should on HEADERS frame initiate a new stream for idle inbound stream (server)") {
    val head = new BasicTail[StreamFrame]("")
    val tools = new MockTools(isClient = false) {
      override lazy val newInboundStream = Some((_: Int) => LeafBuilder(head))
    }

    assert(tools.streamManager.get(1).isEmpty)

    tools.frameListener.onCompleteHeadersFrame(
      streamId = 1,
      priority = Priority.NoPriority,
      endStream = false,
      headers = hs)

    assert(tools.streamManager.get(1).isDefined)
    head.channelRead().value match {
      case Some(Success(HeadersFrame(Priority.NoPriority, false, hss))) =>
        assertEquals(hs, hss.toList)
      case _ => fail("Unexpected channelRead result found")
    }
  }

  test("A SessionFrameListener on PUSH_PROMISE frame server receive push promise") {
    val tools = new MockTools(isClient = false)

    tools.frameListener.onCompletePushPromiseFrame(2, 1, Seq.empty) match {
      case Error(ex: Http2SessionException) =>
        assertEquals(ex.code, PROTOCOL_ERROR.code)
      case _ =>
        fail("Unexpected onCompletePushPromiseFrame result found")
    }
  }

  test("A SessionFrameListener on PUSH_PROMISE frame push promise disabled") {
    val tools = new MockTools(isClient = true)
    tools.localSettings.pushEnabled = false

    val os = tools.streamManager.newOutboundStream()
    os.writeRequest(HeadersFrame(Priority.NoPriority, false, Seq.empty)) // initiate stream

    tools.frameListener.onCompletePushPromiseFrame(os.streamId, os.streamId + 1, hs) match {
      case Error(ex: Http2SessionException) =>
        assertEquals(ex.code, PROTOCOL_ERROR.code)
      case _ =>
        fail("Unexpected onCompletePushPromiseFrame result found")
    }
  }

  test("A SessionFrameListener on PUSH_PROMISE frame delegates to StreamManager") {
    var sId, pId = -1
    var hss: Headers = Nil
    val tools = new MockTools(isClient = true) {
      override lazy val streamManager = new MockStreamManager() {
        override def handlePushPromise(
            streamId: Int,
            promisedId: Int,
            headers: Headers
        ) = {
          sId = streamId
          pId = promisedId
          hss = headers
          Continue
        }
      }
    }

    assertEquals(tools.frameListener.onCompletePushPromiseFrame(1, 2, hs), Continue)
    assertEquals(sId, 1)
    assertEquals(pId, 2)
    assertEquals(hss, hs)
  }

  test("A SessionFrameListener on DATA frame passes it to open streams") {
    val tools = new MockTools(isClient = true)

    val os = tools.streamManager.newOutboundStream()
    os.writeRequest(HeadersFrame(Priority.NoPriority, false, Seq.empty)) // initiate stream

    val data = BufferTools.allocate(4)
    assertEquals(tools.frameListener.onDataFrame(os.streamId, true, data, 4), Continue)

    os.readRequest(1).value match {
      case Some(Success(DataFrame(true, d))) =>
        assertEquals(d, data)
      case _ =>
        fail("Unexpected readRequest result found")
    }
  }

  test(
    "A SessionFrameListener on DATA frame update session flow bytes as consumed for closed streams") {
    val tools = new MockTools(isClient = true)

    val os = tools.streamManager.newOutboundStream()
    os.writeRequest(HeadersFrame(Priority.NoPriority, false, Seq.empty)) // initiate stream
    os.doCloseWithError(None)

    val data = BufferTools.allocate(4)

    val init = tools.sessionFlowControl.sessionInboundWindow
    tools.frameListener.onDataFrame(os.streamId, true, data, 4) match {
      case Error(ex: Http2StreamException) =>
        assertEquals(ex.code, STREAM_CLOSED.code)
      case _ =>
        fail("Unexpected onDataFrame result found")
    }

    assertEquals(tools.sessionFlowControl.sessionInboundWindow, init - 4)
  }

  test("A SessionFrameListener on DATA frame results in GOAWAY(PROTOCOL_ERROR) for idle streams") {
    val tools = new MockTools(isClient = true)

    val init = tools.sessionFlowControl.sessionInboundWindow
    tools.frameListener.onDataFrame(1, true, BufferTools.emptyBuffer, 4) match {
      case Error(ex: Http2SessionException) =>
        assertEquals(ex.code, PROTOCOL_ERROR.code)
      case _ =>
        fail("Unexpected onDataFrame result found")
    }

    assertEquals(tools.sessionFlowControl.sessionInboundWindow, init - 4)
  }

  test("A SessionFrameListener on DATA frame sends RST_STREAM(STREAM_CLOSED) for closed streams") {
    val tools = new MockTools(isClient = true)

    val os = tools.streamManager.newOutboundStream()
    os.writeRequest(HeadersFrame(Priority.NoPriority, false, Seq.empty)) // initiate stream
    os.doCloseWithError(None)

    val data = BufferTools.allocate(4)

    tools.frameListener.onDataFrame(os.streamId, true, data, 4) match {
      case Error(ex: Http2StreamException) =>
        assertEquals(ex.code, STREAM_CLOSED.code)
      case _ =>
        fail("Unexpected onDataFrame result found")
    }
  }

  test("A SessionFrameListener on RST_STREAM delegates to the StreamManager") {
    var observedCause: Option[Http2StreamException] = None
    val tools = new MockTools(true) {
      override lazy val streamManager = new MockStreamManager() {
        override def rstStream(cause: Http2StreamException) = {
          observedCause = Some(cause)
          Continue
        }
      }
    }

    assertEquals(tools.frameListener.onRstStreamFrame(1, STREAM_CLOSED.code), Continue)
    observedCause match {
      case Some(ex) =>
        assertEquals(ex.stream, 1)
        assertEquals(ex.code, STREAM_CLOSED.code)

      case _ =>
        fail("Unexpected result found")
    }
  }

  test("A SessionFrameListener on WINDOW_UPDATE delegates to the StreamManager") {
    var observedIncrement: Option[(Int, Int)] = None
    val tools = new MockTools(true) {
      override lazy val streamManager = new MockStreamManager() {
        override def flowWindowUpdate(streamId: Int, sizeIncrement: Int) = {
          observedIncrement = Some(streamId -> sizeIncrement)
          Continue
        }
      }
    }

    assertEquals(tools.frameListener.onWindowUpdateFrame(1, 2), Continue)
    observedIncrement match {
      case Some((1, 2)) => ()
      case _ => fail("Unexpected result found")
    }
  }

  test("A SessionFrameListener on PING frame writes ACK's for peer initiated PINGs") {
    val tools = new MockTools(true)
    val data = (0 until 8).map(_.toByte).toArray
    assertEquals(tools.frameListener.onPingFrame(false, data), Continue)

    val written = tools.writeController.observedWrites.dequeue()
    assertEquals(written, FrameSerializer.mkPingFrame(ack = true, data = data))
  }

  test("A SessionFrameListener on PING frame pass ping ACK's to the PingManager") {
    var observedAck: Option[Array[Byte]] = None
    val tools = new MockTools(true) {
      override lazy val pingManager = new PingManager(this) {
        override def pingAckReceived(data: Array[Byte]): Unit =
          observedAck = Some(data)
      }
    }
    val data = (0 until 8).map(_.toByte).toArray
    assertEquals(tools.frameListener.onPingFrame(true, data), Continue)
    assertEquals(observedAck, Some(data))
  }

  test("A SessionFrameListener on SETTINGS frame updates remote settings") {
    val tools = new MockTools(true)
    val settingChange = Http2Settings.INITIAL_WINDOW_SIZE(1)
    assertEquals(tools.frameListener.onSettingsFrame(Some(Seq(settingChange))), Continue)

    // Should have changed the setting
    assertEquals(tools.remoteSettings.initialWindowSize, 1)

    // Should have written an ACK
    val written = tools.writeController.observedWrites.dequeue()
    assertEquals(written, FrameSerializer.mkSettingsAckFrame())
  }

  test("A SessionFrameListener on GOAWAY frame delegates to the sessions goAway logic") {
    var observedGoAway: Option[(Int, Http2SessionException)] = None
    val tools = new MockTools(true) {
      override def invokeGoAway(
          lastHandledOutboundStream: Int,
          reason: Http2SessionException
      ): Unit =
        observedGoAway = Some(lastHandledOutboundStream -> reason)
    }

    assertEquals(
      tools.frameListener.onGoAwayFrame(1, NO_ERROR.code, "lol".getBytes(StandardCharsets.UTF_8)),
      Continue)

    observedGoAway match {
      case Some((1, Http2SessionException(NO_ERROR.code, "lol"))) => ()
      case _ => fail("Unexpected result found")
    }
  }
}
