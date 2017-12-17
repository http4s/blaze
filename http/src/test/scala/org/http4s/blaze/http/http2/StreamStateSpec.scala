package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.mocks.MockStreamManager.FinishedStream
import org.http4s.blaze.http.http2.mocks.{MockTools, MockFlowControl}
import org.http4s.blaze.pipeline.Command
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

import scala.util.{Failure, Success}

class StreamStateSpec extends Specification {

  private class Ctx {
    val streamId = 1
    lazy val tools = new MockTools(false)

    val streamState = new InboundStreamState(
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

      val f2 = streamState.writeRequest(DataFrame(true, BufferTools.emptyBuffer))

      foreach(Seq(f1, f2)) { f =>
        f.value match {
          case Some(Failure(_)) => ok
          case other => ko(s"Should have closed everything down: $other")
        }
      }

      val FinishedStream(stream, cause) = tools.streamManager.finishedStreams.dequeue()
      stream must_== streamState
      cause must beSome like {
        case Some(_: Http2StreamException) => ok
      }
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

      val FinishedStream(stream, cause) = tools.streamManager.finishedStreams.dequeue()
      stream must_== streamState
      cause must beSome like {
        case Some(_: Http2StreamException) => ok
      }
    }

    "Close down when receiving Disconnect Command with RST if stream not finished" in {
      val ctx = new Ctx
      import ctx._

      streamState.outboundCommand(Command.Disconnect)

      val FinishedStream(stream, cause) = tools.streamManager.finishedStreams.dequeue()
      stream must_== streamState
      cause must beLike {
        case Some(ex: Http2StreamException) => ex.code must_== Http2Exception.CANCEL.code
      }
    }

    "Close down when receiving Disconnect Command without RST if stream is finished" in {
      val ctx = new Ctx
      import ctx._

      streamState.invokeInboundHeaders(Priority.NoPriority, true, Seq.empty) // remote closed
      streamState.writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty)) // local closed
      streamState.outboundCommand(Command.Disconnect)

      val FinishedStream(stream, cause) = tools.streamManager.finishedStreams.dequeue()
      stream must_== streamState
      cause must beNone
    }

    "Close down when receiving Error Command" in {
      val ctx = new Ctx
      import ctx._

      val ex = new Exception("boom")
      streamState.outboundCommand(Command.Error(ex))
      val FinishedStream(stream, cause) = tools.streamManager.finishedStreams.dequeue()

      stream must_== streamState
      cause must beLike {
        case Some(ex: Http2StreamException) => ex.code must_== Http2Exception.INTERNAL_ERROR.code
      }
    }

    "signal that flow bytes have been consumed to the flow control on complete pending read" in {
      val ctx = new Ctx
      import ctx._

      // Need to open the stream
      streamState.invokeInboundHeaders(Priority.NoPriority, false, Seq.empty) must_== Continue
      streamState.readRequest(1).isCompleted must beTrue // headers

      val f1 = streamState.readRequest(1)
      f1.isCompleted must beFalse
      tools.sessionFlowControl.observedOps.isEmpty must beTrue

      // We should count the flow bytes size, not the actual buffer size
      streamState.invokeInboundData(endStream = false, data = BufferTools.allocate(1), flowBytes = 1) must_== Continue

      val ops = tools.sessionFlowControl.observedOps.result().toSet
      ops must_== Set(
        MockFlowControl.SessionConsumed(1),
        MockFlowControl.StreamConsumed(streamState.flowWindow, 1))
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
      tools.sessionFlowControl.observedOps.isEmpty must beTrue

      val f1 = streamState.readRequest(1)
      f1.isCompleted must beTrue

      val ops = tools.sessionFlowControl.observedOps.result().toSet
      ops must_== Set(
        MockFlowControl.SessionConsumed(1),
        MockFlowControl.StreamConsumed(streamState.flowWindow, 1))
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
