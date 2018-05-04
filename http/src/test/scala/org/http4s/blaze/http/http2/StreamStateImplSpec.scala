package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.mocks.{MockStreamManager, ObservingSessionFlowControl}
import org.http4s.blaze.pipeline.Command
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification
import scala.util.{Failure, Success}

class StreamStateImplSpec extends Specification {

  private class Ctx {
    val streamId = 1

    val streamConsumed = new scala.collection.mutable.Queue[Int]
    val sessionConsumed = new scala.collection.mutable.Queue[Int]

    class MockTools extends mocks.MockTools(isClient = false) {
      override lazy val streamManager: MockStreamManager = new MockStreamManager(isClient = false)

      override lazy val sessionFlowControl: SessionFlowControl =
        new ObservingSessionFlowControl(this) {
          override protected def onSessonBytesConsumed(consumed: Int): Unit = {
            sessionConsumed += consumed
            ()
          }
          override protected def onStreamBytesConsumed(stream: StreamFlowWindow, consumed: Int): Unit = {
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

  "StreamState" should {
    "register a write interest when it is written to" in {
      val ctx = new Ctx
      import ctx._

      val f = streamState.writeRequest(DataFrame(true, BufferTools.emptyBuffer))

      tools.writeController.observedInterests.result must_== streamState::Nil
      tools.writeController.observedInterests.clear()
      f.isCompleted must beFalse
    }

    "not re-register a write interest when flow window is updated" in {
      val ctx = new Ctx
      import ctx._

      val f = streamState.writeRequest(DataFrame(true, BufferTools.emptyBuffer))

      tools.writeController.observedInterests.result must_== streamState::Nil
      tools.writeController.observedInterests.clear()
      f.isCompleted must beFalse

      streamState.outboundFlowWindowChanged()
      tools.writeController.observedInterests.isEmpty must beTrue
    }

    "not allow multiple outstanding writes" in {
      val ctx = new Ctx
      import ctx._

      val f1 = streamState.writeRequest(DataFrame(true, BufferTools.emptyBuffer))
      f1.isCompleted must beFalse
      tools.writeController.observedInterests.result must_== streamState::Nil

      val currentSize = tools.writeController.observedWrites.length

      val f2 = streamState.writeRequest(DataFrame(true, BufferTools.emptyBuffer))

      foreach(Seq(f1, f2)) { f =>
        f.value match {
          case Some(Failure(_)) => ok
          case other => ko(s"Should have closed everything down: $other")
        }
      }


      tools.streamManager.finishedStreams.dequeue() must_== streamState
      // Should have written a RST frame
      tools.writeController.observedWrites.length must_== currentSize + 1
    }

    "not allow multiple outstanding reads" in {
      val ctx = new Ctx
      import ctx._

      val f1 = streamState.readRequest(1)
      f1.isCompleted must beFalse

      val f2 = streamState.readRequest(1)

      foreach(Seq(f1, f2)) { f =>
        f.value match {
          case Some(Failure(_)) => ok
          case other => ko(s"Should have closed everything down: $other")
        }
      }

      tools.streamManager.finishedStreams.dequeue() must_== streamState
      // Should have written a RST frame
      tools.writeController.observedWrites.isEmpty must beFalse
    }

    "Close down when receiving Disconnect Command with RST if stream not finished" in {
      val ctx = new Ctx
      import ctx._

      streamState.outboundCommand(Command.Disconnect)

      tools.streamManager.finishedStreams.dequeue() must_== streamState
      // Should have written a RST frame
      tools.writeController.observedWrites.isEmpty must beFalse
    }

    "Close down when receiving Disconnect Command without RST if stream is finished" in {
      val ctx = new Ctx
      import ctx._

      streamState.invokeInboundHeaders(Priority.NoPriority, true, Seq.empty) // remote closed
      streamState.writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty)) // local closed
      streamState.outboundCommand(Command.Disconnect)

      tools.streamManager.finishedStreams.dequeue() must_== streamState
      // Should *NOT* have written a RST frame
      tools.writeController.observedWrites.isEmpty must beTrue
    }

    "close down when receiving Error Command" in {
      val ctx = new Ctx
      import ctx._

      val ex = new Exception("boom")
      tools.writeController.observedWrites.isEmpty must beTrue
      streamState.outboundCommand(Command.Error(ex))
      tools.streamManager.finishedStreams.dequeue() must_== streamState
      // Should have written a RST frame
      tools.writeController.observedWrites.isEmpty must beFalse
    }

    "close down when receiving Error Command from uninitialized stage" in {
      val ctx = new Ctx {
        override lazy val streamState: StreamStateImpl = new OutboundStreamStateImpl(tools) {
          override protected def registerStream(): Option[Int] = ???
        }
      }
      import ctx._

      val ex = new Exception("boom")
      tools.writeController.observedWrites.isEmpty must beTrue
      streamState.outboundCommand(Command.Error(ex))
      tools.streamManager.finishedStreams.isEmpty must beTrue
      // Shouldn't have written a RST frame
      tools.writeController.observedWrites.isEmpty must beTrue
    }

    "signal that flow bytes have been consumed to the flow control on complete pending read" in {
      val ctx = new Ctx
      import ctx._

      // Need to open the stream
      streamState.invokeInboundHeaders(Priority.NoPriority, false, Seq.empty) must_== Continue
      streamState.readRequest(1).isCompleted must beTrue // headers

      val f1 = streamState.readRequest(1)
      f1.isCompleted must beFalse

      streamConsumed must beEmpty

      // We should count the flow bytes size, not the actual buffer size
      streamState.invokeInboundData(endStream = false, data = BufferTools.allocate(1), flowBytes = 1) must_== Continue

      streamConsumed.dequeue() must_== 1
    }

    "signal that flow bytes have been consumed to the flow control on complete non-pending read" in {
      val ctx = new Ctx
      import ctx._

      // Need to open the stream
      streamState.invokeInboundHeaders(Priority.NoPriority, false, Seq.empty) must_== Continue
      streamState.readRequest(1).isCompleted must beTrue // headers

      // We should count the flow bytes size, not the actual buffer size
      streamState.invokeInboundData(endStream = false, data = BufferTools.allocate(1), flowBytes = 1)

      // Haven't consumed the data yet
      streamConsumed.isEmpty must beTrue

      val f1 = streamState.readRequest(1)
      f1.isCompleted must beTrue

      streamConsumed.dequeue() must_== 1
    }

    "fail result in an session exception if the inbound stream flow window is violated by an inbound message" in {
      val ctx = new Ctx
      import ctx._

      tools.sessionFlowControl.sessionInboundAcked(10)

      assert(streamState.flowWindow.streamInboundWindow < tools.sessionFlowControl.sessionInboundWindow)

      // Need to open the stream
      streamState.invokeInboundHeaders(Priority.NoPriority, false, Seq.empty) must_== Continue

      // We should count the flow bytes size, not the actual buffer size
      streamState.invokeInboundData(
        endStream = false,
        data = BufferTools.emptyBuffer,
        flowBytes = streamState.flowWindow.streamInboundWindow + 1) must beLike {
        case Error(ex: Http2SessionException) => ex.code must_== Http2Exception.FLOW_CONTROL_ERROR.code
      }
    }

    "fail result in an session exception if the inbound session flow window is violated by an inbound message" in {
      val ctx = new Ctx
      import ctx._

      val f1 = streamState.readRequest(1)
      f1.isCompleted must beFalse

      streamState.flowWindow.streamInboundAcked(10)

      assert(streamState.flowWindow.streamInboundWindow > tools.sessionFlowControl.sessionInboundWindow)

      // Need to open the stream
      streamState.invokeInboundHeaders(Priority.NoPriority, false, Seq.empty) must_== Continue

      // We should count the flow bytes size, not the actual buffer size
      streamState.invokeInboundData(
        endStream = false,
        data = BufferTools.emptyBuffer,
        flowBytes = tools.sessionFlowControl.sessionInboundWindow + 1) must beLike {
        case Error(ex: Http2SessionException) => ex.code must_== Http2Exception.FLOW_CONTROL_ERROR.code
      }
    }

    "accepts headers" in {
      val ctx = new Ctx
      import ctx._

      val f1 = streamState.readRequest(1)
      f1.isCompleted must beFalse

      val hs = Seq("foo" -> "bar")

      streamState.invokeInboundHeaders(Priority.NoPriority, false, hs) must_== Continue

      f1.value must beLike {
        case Some(Success(HeadersFrame(Priority.NoPriority, false, hss))) => hs must_== hs
      }
    }

    "accepts data" in {
      val ctx = new Ctx
      import ctx._

      val f1 = streamState.readRequest(1)
      f1.isCompleted must beFalse

      streamState.invokeInboundData(false, BufferTools.allocate(1), 1) must_== Continue

      f1.value must beLike {
        case Some(Success(DataFrame(false, data))) => data.remaining must_== 1
      }
    }
  }
}
