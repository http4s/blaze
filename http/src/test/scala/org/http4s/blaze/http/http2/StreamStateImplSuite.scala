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

import org.http4s.blaze.http.http2.mocks.{MockStreamManager, ObservingSessionFlowControl}
import org.http4s.blaze.testkit.BlazeTestSuite
import org.http4s.blaze.util.BufferTools

import scala.util.{Failure, Success}

class StreamStateImplSuite extends BlazeTestSuite {
  private class Ctx {
    val streamId = 1

    val streamConsumed = new scala.collection.mutable.Queue[Int]
    val sessionConsumed = new scala.collection.mutable.Queue[Int]

    class MockTools extends mocks.MockTools(isClient = false) {
      override lazy val streamManager: MockStreamManager = new MockStreamManager()

      override lazy val sessionFlowControl: SessionFlowControl =
        new ObservingSessionFlowControl(this) {
          override protected def onSessonBytesConsumed(consumed: Int): Unit = {
            sessionConsumed += consumed
            ()
          }
          override protected def onStreamBytesConsumed(
              stream: StreamFlowWindow,
              consumed: Int): Unit = {
            streamConsumed += consumed
            ()
          }
        }
    }

    lazy val tools = new MockTools

    lazy val streamState: StreamStateImpl = new InboundStreamStateImpl(
      session = tools,
      streamId = streamId,
      flowWindow = tools.sessionFlowControl.newStreamFlowWindow(streamId))
  }

  test("A StreamState should register a write interest when it is written to") {
    val ctx = new Ctx
    import ctx._

    val f = streamState.writeRequest(DataFrame(true, BufferTools.emptyBuffer))

    assertEquals(tools.writeController.observedInterests.result(), streamState :: Nil)
    tools.writeController.observedInterests.clear()
    assertEquals(f.isCompleted, false)
  }

  test("A StreamState should not re-register a write interest when flow window is updated") {
    val ctx = new Ctx
    import ctx._

    val f = streamState.writeRequest(DataFrame(true, BufferTools.emptyBuffer))

    assertEquals(tools.writeController.observedInterests.result(), streamState :: Nil)
    tools.writeController.observedInterests.clear()
    assertEquals(f.isCompleted, false)

    streamState.outboundFlowWindowChanged()
    assert(tools.writeController.observedInterests.isEmpty)
  }

  test("A StreamState should not allow multiple outstanding writes") {
    val ctx = new Ctx
    import ctx._

    val f1 = streamState.writeRequest(DataFrame(true, BufferTools.emptyBuffer))
    assertEquals(f1.isCompleted, false)
    assertEquals(tools.writeController.observedInterests.result(), streamState :: Nil)

    val currentSize = tools.writeController.observedWrites.length

    val f2 = streamState.writeRequest(DataFrame(true, BufferTools.emptyBuffer))

    assert(Seq(f1, f2).forall { f =>
      f.value match {
        case Some(Failure(_)) => true
        case _ => false
      }
    })

    assertEquals(tools.streamManager.finishedStreams.dequeue(), streamState)
    // Should have written a RST frame
    assertEquals(tools.writeController.observedWrites.length, currentSize + 1)
  }

  test("A StreamState should not allow multiple outstanding reads") {
    val ctx = new Ctx
    import ctx._

    val f1 = streamState.readRequest(1)
    assertEquals(f1.isCompleted, false)

    val f2 = streamState.readRequest(1)

    assert(Seq(f1, f2).forall { f =>
      f.value match {
        case Some(Failure(_)) => true
        case _ => false
      }
    })

    assertEquals(tools.streamManager.finishedStreams.dequeue(), streamState)
    // Should have written a RST frame
    assertEquals(tools.writeController.observedWrites.isEmpty, false)
  }

  test(
    "A StreamState should close down when receiving Disconnect Command with RST if stream not finished") {
    val ctx = new Ctx
    import ctx._

    streamState.closePipeline(None)

    assertEquals(tools.streamManager.finishedStreams.dequeue(), streamState)
    // Should have written a RST frame
    assertEquals(tools.writeController.observedWrites.isEmpty, false)
  }

  test(
    "A StreamState should close down when receiving Disconnect Command without RST if stream is finished") {
    val ctx = new Ctx
    import ctx._

    streamState.invokeInboundHeaders(Priority.NoPriority, true, Seq.empty) // remote closed
    streamState.writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty)) // local closed
    streamState.closePipeline(None)

    assertEquals(tools.streamManager.finishedStreams.dequeue(), streamState)
    // Should *NOT* have written a RST frame
    assert(tools.writeController.observedWrites.isEmpty)
  }

  test("A StreamState should close down when receiving Error Command") {
    val ctx = new Ctx
    import ctx._

    val ex = new Exception("boom")
    assert(tools.writeController.observedWrites.isEmpty)
    streamState.closePipeline(Some(ex))
    assertEquals(tools.streamManager.finishedStreams.dequeue(), streamState)
    // Should have written a RST frame
    assertEquals(tools.writeController.observedWrites.isEmpty, false)
  }

  test("A StreamState should close down when receiving Error Command from uninitialized stage") {
    val ctx = new Ctx {
      override lazy val streamState: StreamStateImpl = new OutboundStreamStateImpl(tools) {
        override protected def registerStream(): Option[Int] = ???
      }
    }
    import ctx._

    val ex = new Exception("boom")
    assert(tools.writeController.observedWrites.isEmpty)
    streamState.closePipeline(Some(ex))
    assert(tools.streamManager.finishedStreams.isEmpty)
    // Shouldn't have written a RST frame
    assert(tools.writeController.observedWrites.isEmpty)
  }

  test(
    "A StreamState should signal that flow bytes have been consumed to the flow control on complete pending read") {
    val ctx = new Ctx
    import ctx._

    // Need to open the stream
    assertEquals(streamState.invokeInboundHeaders(Priority.NoPriority, false, Seq.empty), Continue)
    assert(streamState.readRequest(1).isCompleted) // headers

    val f1 = streamState.readRequest(1)
    assertEquals(f1.isCompleted, false)

    assert(streamConsumed.isEmpty)

    // We should count the flow bytes size, not the actual buffer size
    assertEquals(
      streamState.invokeInboundData(
        endStream = false,
        data = BufferTools.allocate(1),
        flowBytes = 1),
      Continue)

    assertEquals(streamConsumed.dequeue(), 1)
  }

  test(
    "A StreamState should signal that flow bytes have been consumed to the flow control on complete non-pending read") {
    val ctx = new Ctx
    import ctx._

    // Need to open the stream
    assertEquals(streamState.invokeInboundHeaders(Priority.NoPriority, false, Seq.empty), Continue)
    assert(streamState.readRequest(1).isCompleted) // headers

    // We should count the flow bytes size, not the actual buffer size
    streamState.invokeInboundData(endStream = false, data = BufferTools.allocate(1), flowBytes = 1)

    // Haven't consumed the data yet
    assert(streamConsumed.isEmpty)

    val f1 = streamState.readRequest(1)
    assert(f1.isCompleted)

    assertEquals(streamConsumed.dequeue(), 1)
  }

  test(
    "A StreamState should fail result in an session exception if the inbound " +
      "stream flow window is violated by an inbound message") {
    val ctx = new Ctx
    import ctx._

    tools.sessionFlowControl.sessionInboundAcked(10)

    assert(
      streamState.flowWindow.streamInboundWindow < tools.sessionFlowControl.sessionInboundWindow)

    // Need to open the stream
    assertEquals(streamState.invokeInboundHeaders(Priority.NoPriority, false, Seq.empty), Continue)

    // We should count the flow bytes size, not the actual buffer size
    streamState.invokeInboundData(
      endStream = false,
      data = BufferTools.emptyBuffer,
      flowBytes = streamState.flowWindow.streamInboundWindow + 1) match {
      case Error(ex: Http2SessionException) =>
        assertEquals(ex.code, Http2Exception.FLOW_CONTROL_ERROR.code)
      case _ =>
        fail("Unexpected invokeInboundData result found")
    }
  }

  test(
    "A StreamState should fail result in an session exception if the inbound session " +
      "flow window is violated by an inbound message") {
    val ctx = new Ctx
    import ctx._

    val f1 = streamState.readRequest(1)
    assertEquals(f1.isCompleted, false)

    streamState.flowWindow.streamInboundAcked(10)

    assert(
      streamState.flowWindow.streamInboundWindow > tools.sessionFlowControl.sessionInboundWindow)

    // Need to open the stream
    assertEquals(streamState.invokeInboundHeaders(Priority.NoPriority, false, Seq.empty), Continue)

    // We should count the flow bytes size, not the actual buffer size
    streamState.invokeInboundData(
      endStream = false,
      data = BufferTools.emptyBuffer,
      flowBytes = tools.sessionFlowControl.sessionInboundWindow + 1) match {
      case Error(ex: Http2SessionException) =>
        assertEquals(ex.code, Http2Exception.FLOW_CONTROL_ERROR.code)
      case _ =>
        fail("Unexpected invokeInboundData result found")
    }
  }

  test("A StreamState should accepts headers") {
    val ctx = new Ctx
    import ctx._

    val f1 = streamState.readRequest(1)
    assertEquals(f1.isCompleted, false)

    val hs = Seq("foo" -> "bar")

    assertEquals(streamState.invokeInboundHeaders(Priority.NoPriority, false, hs), Continue)

    f1.value match {
      case Some(Success(HeadersFrame(Priority.NoPriority, false, hss))) =>
        assertEquals(hss, hs)
      case _ =>
        fail("Unexpected readRequest result found")
    }
  }

  test("A StreamState should accepts data") {
    val ctx = new Ctx
    import ctx._

    val f1 = streamState.readRequest(1)
    assertEquals(f1.isCompleted, false)

    assertEquals(streamState.invokeInboundData(false, BufferTools.allocate(1), 1), Continue)

    f1.value match {
      case Some(Success(DataFrame(false, data))) =>
        assertEquals(data.remaining, 1)
      case _ =>
        fail("Unexpected readRequest result found")
    }
  }
}
