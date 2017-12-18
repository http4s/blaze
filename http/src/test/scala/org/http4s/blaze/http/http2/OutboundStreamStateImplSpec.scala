package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.Http2Connection.{Closed, ConnectionState, Running}
import org.http4s.blaze.http.http2.mocks.{MockStreamManager, MockTools}
import org.specs2.mutable.Specification

import scala.concurrent.duration.Duration
import scala.util.Failure

class OutboundStreamStateImplSpec extends Specification {

  private class Ctx(acceptStream: Boolean, connectionState: ConnectionState) {
    val streamId = 1
    lazy val tools = new MockTools(false) {
      override lazy val streamManager: MockStreamManager = new MockStreamManager {
        override def registerOutboundStream(state: OutboundStreamState): Option[Int] = {
          if (acceptStream) super.registerOutboundStream(state)
          else None
        }
      }

      override def state: ConnectionState = connectionState
    }
    val streamState = new OutboundStreamStateImpl(session = tools)
  }

  "OutboundStreamState" should {
    "initialize a flow window and stream id lazily" in {
      val ctx = new Ctx(true, Running)
      import ctx._

      streamState.streamId must throwAn[IllegalStateException]
      streamState.writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty))
      tools.streamManager.registeredOutboundStreams.dequeue() must_== streamState
      streamState.streamId must_== 1 // it's hard coded to 1
    }

    "fail write requests if we fail to acquire a stream ID" in {
      val ctx = new Ctx(false, Running)
      import ctx._

      val f = streamState.writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty))
      tools.drainGracePeriod must_== Some(Duration.Inf)
      tools.streamManager.registeredOutboundStreams must beEmpty
      f.value must beLike {
        case Some(Failure(ex: Http2StreamException)) => ex.code must_== Http2Exception.REFUSED_STREAM.code
      }
    }

    "fail write requests if the session is closing" in {
      val ctx = new Ctx(true, Closed)
      import ctx._

      val f = streamState.writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty))
      tools.streamManager.registeredOutboundStreams must beEmpty
      f.value must beLike {
        case Some(Failure(ex: Http2StreamException)) => ex.code must_== Http2Exception.REFUSED_STREAM.code
      }
    }
  }

}
