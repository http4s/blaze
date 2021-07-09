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

import org.http4s.blaze.http.http2.Http2Exception._
import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage}
import org.http4s.blaze.testkit.BlazeTestSuite

import scala.util.Failure

class StreamManagerImplSuite extends BlazeTestSuite {
  private class MockTools(isClient: Boolean) extends mocks.MockTools(isClient) {
    override lazy val streamManager: StreamManager =
      new StreamManagerImpl(this, if (isClient) None else Some(_ => newInboundStream()))

    var connects: Int = 0

    lazy val tail = new TailStage[StreamFrame] {
      override def name: String = "name"
      override def inboundCommand(cmd: Command.InboundCommand): Unit = {
        if (cmd == Command.Connected)
          connects += 1
        super.inboundCommand(cmd)
      }
    }

    private def newInboundStream() = LeafBuilder(tail)
  }

  test("A StreamManagerImpl close streams should force close") {
    val tools = new MockTools(isClient = false)

    // inbound stream for the client are even numbered
    val Right(s1) = tools.streamManager.newInboundStream(1)
    val Right(s3) = tools.streamManager.newInboundStream(3)

    assertEquals(tools.connects, 2)

    val ex = new Exception("boom")
    tools.streamManager.forceClose(Some(ex))

    // further calls to drain should happen immediately
    assert(
      tools.streamManager
        .drain(100, Http2Exception.NO_ERROR.goaway("whatever"))
        .isCompleted)

    // Since the streams are closed stream operations should fail
    val hs = HeadersFrame(Priority.NoPriority, false, Seq.empty)
    s1.writeRequest(hs).value match {
      case Some(Failure(`ex`)) => ()
      case _ => fail("Unexpected writeRequest result found")
    }

    s3.writeRequest(hs).value match {
      case Some(Failure(`ex`)) => ()
      case _ => fail("Unexpected writeRequest result found")
    }
  }

  test("A StreamManagerImpl close streams should drain via goAway") {
    val tools = new MockTools(isClient = false)

    val os2 = tools.streamManager.newOutboundStream()
    val os4 = tools.streamManager.newOutboundStream()

    // each needs to write some data to initialize
    os2.writeRequest(HeadersFrame(Priority.NoPriority, false, Seq.empty))
    val f4 = os4.writeRequest(HeadersFrame(Priority.NoPriority, false, Seq.empty))

    val f = tools.streamManager.drain(2, Http2Exception.NO_ERROR.goaway("bye-bye"))
    assertEquals(f.isCompleted, false)

    f4.value match {
      case Some(Failure(ex: Http2Exception)) =>
        assertEquals(ex.code, REFUSED_STREAM.code)

      case _ =>
        fail("Unexpected writeRequest result found")
    }

    tools.streamManager.streamClosed(os2)
    assert(f.isCompleted)
  }

  test(
    "A StreamManagerImpl close streams should new streams are rejected after a GOAWAY is issued") {
    val tools = new MockTools(isClient = false)

    // Need a stream so it doesn't all shut down
    assert(tools.streamManager.newInboundStream(1).isRight)
    tools.streamManager.drain(1, Http2Exception.NO_ERROR.goaway("bye-bye"))

    tools.streamManager.newInboundStream(3) match {
      case Left(ex: Http2StreamException) =>
        assertEquals(ex.code, REFUSED_STREAM.code)
      case _ => fail("Unexpected newInboundStream result found")
    }

    tools.streamManager
      .newOutboundStream()
      .writeRequest(HeadersFrame(Priority.NoPriority, false, Seq.empty))
      .value match {
      case Some(Failure(ex: Http2StreamException)) =>
        assertEquals(ex.code, REFUSED_STREAM.code)
      case _ =>
        fail("Unexpected writeRequest result found")
    }
  }

  test("A StreamManagerImpl create streams should create inbound streams (server)") {
    val tools = new MockTools(isClient = false)

    // inbound stream for the client are even numbered
    // https://tools.ietf.org/html/rfc7540#section-5.1.1
    val Right(s1) = tools.streamManager.newInboundStream(1)
    val Right(s3) = tools.streamManager.newInboundStream(3)

    assertEquals(tools.streamManager.get(1), Some(s1))
    assertEquals(tools.streamManager.get(3), Some(s3))
    assert(tools.streamManager.get(5).isEmpty)
  }

  test("A StreamManagerImpl create streams should reject inbound streams (client)") {
    val tools = new MockTools(isClient = true)

    // inbound stream for the client are even numbered
    // https://tools.ietf.org/html/rfc7540#section-5.1.1
    tools.streamManager.newInboundStream(2) match {
      case Left(ex: Http2SessionException) =>
        assertEquals(ex.code, PROTOCOL_ERROR.code)
      case _ =>
        fail("Unexpected newInboundStream result found")
    }
  }

  test(
    "A StreamManagerImpl create streams should reject inbound streams with for non-idle streams") {
    val tools = new MockTools(isClient = false)

    assert(tools.streamManager.newInboundStream(1).isRight)

    // Reject invalid stream ids
    tools.streamManager.newInboundStream(1) match {
      case Left(ex: Http2SessionException) =>
        assertEquals(ex.code, PROTOCOL_ERROR.code)
      case _ =>
        fail("Unexpected newInboundStream result found")
    }
  }

  test(
    "A StreamManagerImpl create streams should reject inbound streams when MAX_CONCURRENT_STREAMS hit") {
    val tools = new MockTools(isClient = false)

    tools.localSettings.maxConcurrentStreams = 1
    val Right(s) = tools.streamManager.newInboundStream(1) // should work

    // Too many streams!
    tools.streamManager.newInboundStream(3) match {
      case Left(ex: Http2StreamException) =>
        assertEquals(ex.code, REFUSED_STREAM.code)
      case _ =>
        fail("Unexpected newInboundStream result found")
    }

    s.doCloseWithError(None)

    // Now we can make a new stream
    tools.streamManager.newInboundStream(5) match {
      case Right(_) => ()
      case _ => fail("Unexpected newInboundStream result found")
    }
  }

  test("A StreamManagerImpl create streams should create outbound streams") {
    val tools = new MockTools(isClient = true)

    // inbound stream for the client are odd numbered
    // https://tools.ietf.org/html/rfc7540#section-5.1.1

    // Explicitly reversed to make sure order doesn't matter
    val oss3 = tools.streamManager.newOutboundStream()
    val oss1 = tools.streamManager.newOutboundStream()

    // Shouldn't be registered yet since they haven't written anything
    assert(tools.streamManager.get(1).isEmpty)
    assert(tools.streamManager.get(3).isEmpty)

    val hs = HeadersFrame(Priority.NoPriority, false, Seq.empty)
    oss1.writeRequest(hs)
    oss3.writeRequest(hs)

    assertEquals(tools.streamManager.get(1), Some(oss1))
    assertEquals(tools.streamManager.get(3), Some(oss3))
    assert(tools.streamManager.get(5).isEmpty)
  }

  test(
    "A StreamManagerImpl flow windows should update streams flow window on a successful initial flow window change") {
    // https://tools.ietf.org/html/rfc7540#section-6.9.2
    val tools = new MockTools(isClient = false)

    val Right(s) = tools.streamManager.newInboundStream(1)
    val startFlowWindow = s.flowWindow.outboundWindow
    assertEquals(tools.streamManager.initialFlowWindowChange(1), Continue)
    assertEquals(s.flowWindow.streamOutboundWindow, (startFlowWindow + 1))
  }

  test(
    "A StreamManagerImpl flow windows should close streams flow window on a failed initial flow window change") {
    // https://tools.ietf.org/html/rfc7540#section-6.9.2
    val tools = new MockTools(isClient = false)

    val Right(s) = tools.streamManager.newInboundStream(1)
    val delta = Int.MaxValue - s.flowWindow.outboundWindow
    assert(s.flowWindow.streamOutboundAcked(delta).isEmpty)
    assertEquals(s.flowWindow.streamOutboundWindow, Int.MaxValue)

    tools.streamManager.initialFlowWindowChange(1) match {
      case Error(ex: Http2SessionException) =>
        assertEquals(ex.code, FLOW_CONTROL_ERROR.code)
      case _ =>
        fail("Unexpected initialFlowWindowChange result found")
    }
  }

  test(
    "A StreamManagerImpl flow windows should results in GOAWAY(PROTOCOL_ERROR) for update on idle stream") {
    new MockTools(isClient = true).streamManager
      .flowWindowUpdate(1, 1) match {
      case Error(ex: Http2SessionException) =>
        assertEquals(ex.code, PROTOCOL_ERROR.code)
      case _ =>
        fail("Unexpected flowWindowUpdate result found")
    }
  }

  test(
    "A StreamManagerImpl flow windows should handle successful flow window updates for streams") {
    val tools = new MockTools(isClient = false)

    val Right(s) = tools.streamManager.newInboundStream(1)
    val initFlowWindow = s.flowWindow.outboundWindow
    assertEquals(tools.streamManager.flowWindowUpdate(streamId = 1, sizeIncrement = 1), Continue)
    assertEquals(s.flowWindow.streamOutboundWindow, (initFlowWindow + 1))
  }

  test("A StreamManagerImpl flow windows should handle failed flow window updates for streams") {
    val tools = new MockTools(isClient = false)

    val Right(s) = tools.streamManager.newInboundStream(1)
    assert(
      s.flowWindow.streamOutboundAcked(Int.MaxValue - s.flowWindow.streamOutboundWindow).isEmpty)

    tools.streamManager.flowWindowUpdate(streamId = 1, sizeIncrement = 1) match {
      case Error(ex: Http2StreamException) =>
        assertEquals(ex.code, FLOW_CONTROL_ERROR.code)
      case _ =>
        fail("Unexpected flowWindowUpdate result found")
    }
  }

  test(
    "A StreamManagerImpl flow windows should handle successful flow window updates for the session") {
    var sessionAcked: Option[Int] = None
    val tools = new MockTools(true) {
      override lazy val sessionFlowControl: SessionFlowControl = new MockSessionFlowControl {
        override def sessionOutboundAcked(count: Int): Option[Http2Exception] = {
          sessionAcked = Some(count)
          None
        }
      }
    }

    assertEquals(tools.streamManager.flowWindowUpdate(0, 100), Continue)
    assertEquals(sessionAcked, Some(100))
  }

  test(
    "A StreamManagerImpl flow windows should handle failed flow window updates for the session") {
    var sessionAcked: Option[Int] = None
    val tools = new MockTools(true) {
      override lazy val sessionFlowControl: SessionFlowControl = new MockSessionFlowControl {
        override def sessionOutboundAcked(count: Int): Option[Http2Exception] = {
          sessionAcked = Some(count)
          Some(FLOW_CONTROL_ERROR.goaway("boom"))
        }
      }
    }

    tools.streamManager.flowWindowUpdate(0, 100) match {
      case Error(ex: Http2SessionException) =>
        assertEquals(ex.code, FLOW_CONTROL_ERROR.code)
      case _ =>
        fail("Unexpected flowWindowUpdate result found")
    }
    assertEquals(sessionAcked, Some(100))
  }

  test(
    "A StreamManagerImpl flow windows should PUSH_PROMISE frames are rejected by default by the client") {
    val tools = new MockTools(isClient = true)

    tools.streamManager
      .newOutboundStream()
      .writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty))

    tools.streamManager.handlePushPromise(streamId = 1, promisedId = 2, headers = Seq.empty) match {
      case Error(ex: Http2StreamException) =>
        assertEquals(ex.code, REFUSED_STREAM.code)
        assertEquals(ex.stream, 2)

      case _ =>
        fail("Unexpected handlePushPromise result found")
    }
  }

  test("A StreamManagerImpl flow windows should PUSH_PROMISE frames are rejected by the server") {
    val tools = new MockTools(isClient = false)

    tools.streamManager.handlePushPromise(1, 2, Seq.empty) match {
      case Error(ex: Http2SessionException) =>
        assertEquals(ex.code, PROTOCOL_ERROR.code)
      case _ =>
        fail("Unexpected handlePushPromise result found")
    }
  }

  test("A StreamManagerImpl flow windows should PUSH_PROMISE with idle associated stream") {
    val tools = new MockTools(isClient = true)

    tools.streamManager.handlePushPromise(1, 2, Seq.empty) match {
      case Error(ex: Http2SessionException) =>
        assertEquals(ex.code, PROTOCOL_ERROR.code)
      case _ =>
        fail("Unexpected handlePushPromise result found")
    }
  }

  test("A StreamManagerImpl flow windows should PUSH_PROMISE with closed associated stream") {
    val tools = new MockTools(isClient = true)
    val streamId = tools.idManager.takeOutboundId().getOrElse(sys.error("failed to acquire id"))

    // We don't accept the stream because we don't support it, but it should
    // just be a RST_STREAM(REFUSED_STREAM) response
    tools.streamManager.handlePushPromise(streamId, 2, Seq.empty) match {
      case Error(ex: Http2StreamException) =>
        assertEquals(ex.code, REFUSED_STREAM.code)
        assertEquals(ex.stream, 2)
      case _ =>
        fail("Unexpected handlePushPromise result found")
    }
  }

  test(
    "A StreamManagerImpl flow windows should PUSH_PROMISE with promised stream which is not idle") {
    val promisedId = 2
    val tools = new MockTools(isClient = true)
    val streamId = tools.idManager.takeOutboundId().getOrElse(sys.error("failed to acquire id"))
    tools.idManager.observeInboundId(promisedId)

    tools.streamManager.handlePushPromise(streamId, promisedId, Seq.empty) match {
      case Error(ex: Http2SessionException) =>
        assertEquals(ex.code, PROTOCOL_ERROR.code)
      case _ =>
        fail("Unexpected handlePushPromise result found")
    }
  }
}
