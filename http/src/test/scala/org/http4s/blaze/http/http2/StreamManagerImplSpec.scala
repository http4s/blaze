package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.Http2Exception._
import org.http4s.blaze.http.http2.mocks.{MockInboundStreamState, MockOutboundStreamState, MockStreamFlowWindow, MockTools}
import org.specs2.mutable.Specification

class StreamManagerImplSpec extends Specification {
  private class Ctx {
    lazy val tools = new MockTools(isClient = true)
    lazy val streamManager = new StreamManagerImpl(tools, StreamIdManager(isClient = true))
  }

  private class ISS(streamId: Int) extends MockInboundStreamState(streamId) {
    var observedError: Option[Option[Throwable]] = None
    override def closeWithError(t: Option[Throwable]): Unit = {
      observedError = Some(t)
    }

    var initialWindowChange: Option[Int] = None
    override val flowWindow: StreamFlowWindow = new MockStreamFlowWindow {
      override def peerSettingsInitialWindowChange(delta: Int): MaybeError = {
        initialWindowChange = Some(delta)
        Continue
      }
    }
  }



  "StreamManagerImpl" should {
    "close streams" in {

      class ISS(streamId: Int) extends MockInboundStreamState(streamId) {
        var observedError: Option[Option[Throwable]] = None
        override def closeWithError(t: Option[Throwable]): Unit = {
          observedError = Some(t)
        }
      }

      class OSS extends MockOutboundStreamState {
        var sid: Int = -1
        override def streamId: Int = sid

        var observedError: Option[Option[Throwable]] = None
        override def closeWithError(t: Option[Throwable]): Unit = {
          observedError = Some(t)
        }
      }

      "force close" in {
        val ctx = new Ctx
        import ctx._

        val iss2 = new ISS(2) // inbound stream for the client are even numbered
        val iss4 = new ISS(4)

        streamManager.registerInboundStream(iss2)
        streamManager.registerInboundStream(iss4)

        val ex = new Exception("boom")
        streamManager.forceClose(Some(ex))

        // further calls to drain should happen immediately
        streamManager.goAway(100, "whatever").isCompleted must beTrue
        iss2.observedError must beLike {
          case Some(Some(e)) => e must_== ex
        }

        iss4.observedError must beLike {
          case Some(Some(e)) => e must_== ex
        }
      }

      "drain via goaway" in {
        val ctx = new Ctx
        import ctx._

        val oss2 = new OSS
        val oss4 = new OSS

        streamManager.registerOutboundStream(oss2) must beLike {
          case Some(sid) =>
            oss2.sid = sid
            ok
        }
        streamManager.registerOutboundStream(oss4) must beLike {
          case Some(sid) =>
            oss4.sid = sid
            ok
        }

        val f = streamManager.goAway(2, "bye-bye")
        f.isCompleted must beFalse

        oss2.observedError must beNone
        oss4.observedError must beLike {
          case Some(Some(ex: Http2StreamException)) => ex.code must_== REFUSED_STREAM.code
        }

        streamManager.streamClosed(oss2)
        f.isCompleted must beTrue
      }

      "new streams are rejected after a goaway is issued" in {
        val ctx = new Ctx
        import ctx._

        val oss2 = new OSS


        streamManager.registerOutboundStream(oss2) must beLike {
          case Some(sid) =>
            oss2.sid = sid
            ok
        }

        streamManager.goAway(2, "bye-bye")
        streamManager.registerOutboundStream(new OSS) must beNone
        streamManager.registerInboundStream(new ISS(2)) must beFalse
      }
    }

    "register streams" in {
      "register inbound streams" in {
        val ctx = new Ctx
        import ctx._

        // inbound stream for the client are even numbered
        // https://tools.ietf.org/html/rfc7540#section-5.1.1
        val iss2 = new ISS(2)
        val iss4 = new ISS(4)

        streamManager.registerInboundStream(iss2) must beTrue
        streamManager.registerInboundStream(iss4) must beTrue

        streamManager.get(2) must beSome(iss2)
        streamManager.get(4) must beSome(iss4)
        streamManager.get(6) must beNone
      }

      "register outbound streams" in {
        class OSS extends MockOutboundStreamState {
          var sid: Int = -1
          override def streamId: Int = sid
        }

        val ctx = new Ctx
        import ctx._

        // inbound stream for the client are odd numbered
        // https://tools.ietf.org/html/rfc7540#section-5.1.1
        val oss1 = new OSS
        val oss3 = new OSS

        streamManager.registerOutboundStream(oss1) must beLike {
          case Some(sid) =>
            oss1.sid = sid
            ok
        }
        streamManager.registerOutboundStream(oss3) must beLike {
          case Some(sid) =>
            oss3.sid = sid
            ok
        }

        streamManager.get(1) must beSome(oss1)
        streamManager.get(3) must beSome(oss3)
        streamManager.get(5) must beNone
      }
    }

    "flow windows" in {
      class ISSFlow(streamId: Int, settingResult: MaybeError) extends MockInboundStreamState(streamId) {
        var initialWindowChange: Option[Int] = None
        override val flowWindow: StreamFlowWindow = new MockStreamFlowWindow {
          override def peerSettingsInitialWindowChange(delta: Int): MaybeError = {
            initialWindowChange = Some(delta)
            settingResult
          }
        }
      }

      "update streams flow window on a successful initial flow window change" in {
        // https://tools.ietf.org/html/rfc7540#section-6.9.2
        val ctx = new Ctx
        import ctx._
        val iss2 = new ISSFlow(2, Continue)
        streamManager.registerInboundStream(iss2) must beTrue
        streamManager.initialFlowWindowChange(100) must_== Continue
        iss2.initialWindowChange must beSome(100)
        iss2.calledOutboundFlowWindowChanged must beTrue
      }

      "close streams flow window on a failed initial flow window change" in {
        // https://tools.ietf.org/html/rfc7540#section-6.9.2
        val ctx = new Ctx
        import ctx._
        val iss2 = new ISSFlow(2, Error(Http2Exception.FLOW_CONTROL_ERROR.goaway("overflowed")))
        streamManager.registerInboundStream(iss2) must beTrue
        streamManager.initialFlowWindowChange(100) must beLike {
          case Error(ex: Http2SessionException) => ex.code must_== FLOW_CONTROL_ERROR.code
        }
        iss2.initialWindowChange must beSome(100)
        iss2.calledOutboundFlowWindowChanged must beFalse
      }

      class ISSWindow(streamId: Int, flowResult: MaybeError) extends MockInboundStreamState(streamId) {

        var outboundAcked: Option[Int] = None
        override val flowWindow: StreamFlowWindow = new MockStreamFlowWindow {
          override def streamOutboundAcked(count: Int): MaybeError = {
            outboundAcked = Some(count)
            flowResult
          }
        }
      }

      "handle successful flow window updates for streams" in {
        val ctx = new Ctx
        import ctx._

        val iss2 = new ISSWindow(2, Continue)
        streamManager.registerInboundStream(iss2) must beTrue
        streamManager.flowWindowUpdate(2, 100) must_== Continue
        iss2.calledOutboundFlowWindowChanged must beTrue
      }

      "handle failed flow window updates for streams" in {
        val ctx = new Ctx
        import ctx._

        val iss2 = new ISSWindow(2, Error(FLOW_CONTROL_ERROR.rst(2)))
        streamManager.registerInboundStream(iss2) must beTrue
        streamManager.flowWindowUpdate(2, 100) must beLike {
          case Error(ex: Http2StreamException) => ex.code must_== FLOW_CONTROL_ERROR.code
        }
        iss2.calledOutboundFlowWindowChanged must beFalse
      }

      "handle successful flow window updates for the session" in {
        var sessionAcked: Option[Int] = None
        val ctx = new Ctx {
          override lazy val tools = new MockTools(true) {
            override lazy val sessionFlowControl: SessionFlowControl = new MockSessionFlowControl {
              override def sessionOutboundAcked(count: Int): MaybeError = {
                sessionAcked = Some(count)
                Continue
              }
            }
          }

        }
        import ctx._

        streamManager.flowWindowUpdate(0, 100) must_== Continue
        sessionAcked must beSome(100)
      }

      "handle failed flow window updates for the session" in {
        var sessionAcked: Option[Int] = None
        val ctx = new Ctx {
          override lazy val tools = new MockTools(true) {
            override lazy val sessionFlowControl: SessionFlowControl = new MockSessionFlowControl {
              override def sessionOutboundAcked(count: Int): MaybeError = {
                sessionAcked = Some(count)
                Error(FLOW_CONTROL_ERROR.goaway("boom"))
              }
            }
          }

        }
        import ctx._

        streamManager.flowWindowUpdate(0, 100) must beLike {
          case Error(ex: Http2SessionException) => ex.code must_== FLOW_CONTROL_ERROR.code
        }
        sessionAcked must beSome(100)
      }
    }

    "PUSH_PROMISE frames are rejected by default" in {
      val ctx = new Ctx
      import ctx._

      streamManager.handlePushPromise(1, 2, Seq.empty) must_== Continue
      // We should have written a RST_STREAM frame to abort the stream
      tools.writeController.observedWrites must not(beEmpty)
    }
  }

}
