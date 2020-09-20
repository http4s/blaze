/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.Http2Exception._
import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage}
import org.specs2.mutable.Specification
import scala.util.Failure

class StreamManagerImplSpec extends Specification {
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

  "StreamManagerImpl" should {
    "close streams" in {
      "force close" in {
        val tools = new MockTools(isClient = false)

        // inbound stream for the client are even numbered
        val Right(s1) = tools.streamManager.newInboundStream(1)
        val Right(s3) = tools.streamManager.newInboundStream(3)

        tools.connects must_== 2

        val ex = new Exception("boom")
        tools.streamManager.forceClose(Some(ex))

        // further calls to drain should happen immediately
        tools.streamManager
          .drain(100, Http2Exception.NO_ERROR.goaway("whatever"))
          .isCompleted must beTrue

        // Since the streams are closed stream operations should fail
        val hs = HeadersFrame(Priority.NoPriority, false, Seq.empty)
        s1.writeRequest(hs).value must beLike { case Some(Failure(`ex`)) =>
          ok
        }

        s3.writeRequest(hs).value must beLike { case Some(Failure(`ex`)) =>
          ok
        }
      }

      "drain via goAway" in {
        val tools = new MockTools(isClient = false)

        val os2 = tools.streamManager.newOutboundStream()
        val os4 = tools.streamManager.newOutboundStream()

        // each needs to write some data to initialize
        os2.writeRequest(HeadersFrame(Priority.NoPriority, false, Seq.empty))
        val f4 = os4.writeRequest(HeadersFrame(Priority.NoPriority, false, Seq.empty))

        val f = tools.streamManager.drain(2, Http2Exception.NO_ERROR.goaway("bye-bye"))
        f.isCompleted must beFalse

        f4.value must beLike { case Some(Failure(ex: Http2Exception)) =>
          ex.code must_== REFUSED_STREAM.code
        }

        tools.streamManager.streamClosed(os2)
        f.isCompleted must beTrue
      }

      "new streams are rejected after a GOAWAY is issued" in {
        val tools = new MockTools(isClient = false)

        // Need a stream so it doesn't all shut down
        val Right(_) = tools.streamManager.newInboundStream(1)
        tools.streamManager.drain(1, Http2Exception.NO_ERROR.goaway("bye-bye"))

        tools.streamManager.newInboundStream(3) must beLike { case Left(ex: Http2StreamException) =>
          ex.code must_== REFUSED_STREAM.code
        }

        tools.streamManager
          .newOutboundStream()
          .writeRequest(HeadersFrame(Priority.NoPriority, false, Seq.empty))
          .value must beLike { case Some(Failure(ex: Http2StreamException)) =>
          ex.code must_== REFUSED_STREAM.code
        }
      }
    }

    "create streams" in {
      "create inbound streams (server)" in {
        val tools = new MockTools(isClient = false)

        // inbound stream for the client are even numbered
        // https://tools.ietf.org/html/rfc7540#section-5.1.1
        val Right(s1) = tools.streamManager.newInboundStream(1)
        val Right(s3) = tools.streamManager.newInboundStream(3)

        tools.streamManager.get(1) must beSome(s1)
        tools.streamManager.get(3) must beSome(s3)
        tools.streamManager.get(5) must beNone
      }

      "Reject inbound streams (client)" in {
        val tools = new MockTools(isClient = true)

        // inbound stream for the client are even numbered
        // https://tools.ietf.org/html/rfc7540#section-5.1.1
        tools.streamManager.newInboundStream(2) must beLike {
          case Left(ex: Http2SessionException) => ex.code must_== PROTOCOL_ERROR.code
        }
      }

      "reject inbound streams with for non-idle streams" in {
        val tools = new MockTools(isClient = false)

        val Right(_) = tools.streamManager.newInboundStream(1)

        // Reject invalid stream ids
        tools.streamManager.newInboundStream(1) must beLike {
          case Left(ex: Http2SessionException) => ex.code must_== PROTOCOL_ERROR.code
        }
      }

      "reject inbound streams when MAX_CONCURRENT_STREAMS hit" in {
        val tools = new MockTools(isClient = false)

        tools.localSettings.maxConcurrentStreams = 1
        val Right(s) = tools.streamManager.newInboundStream(1) // should work

        // Too many streams!
        tools.streamManager.newInboundStream(3) must beLike { case Left(ex: Http2StreamException) =>
          ex.code must_== REFUSED_STREAM.code
        }

        s.doCloseWithError(None)

        // Now we can make a new stream
        tools.streamManager.newInboundStream(5) must beLike { case Right(_) =>
          ok
        }
      }

      "create outbound streams" in {
        val tools = new MockTools(isClient = true)

        // inbound stream for the client are odd numbered
        // https://tools.ietf.org/html/rfc7540#section-5.1.1

        // Explicitly reversed to make sure order doesn't matter
        val oss3 = tools.streamManager.newOutboundStream()
        val oss1 = tools.streamManager.newOutboundStream()

        // Shouldn't be registered yet since they haven't written anything
        tools.streamManager.get(1) must beNone
        tools.streamManager.get(3) must beNone

        val hs = HeadersFrame(Priority.NoPriority, false, Seq.empty)
        oss1.writeRequest(hs)
        oss3.writeRequest(hs)

        tools.streamManager.get(1) must beSome(oss1)
        tools.streamManager.get(3) must beSome(oss3)
        tools.streamManager.get(5) must beNone
      }
    }

    "flow windows" in {
      "update streams flow window on a successful initial flow window change" in {
        // https://tools.ietf.org/html/rfc7540#section-6.9.2
        val tools = new MockTools(isClient = false)

        val Right(s) = tools.streamManager.newInboundStream(1)
        val startFlowWindow = s.flowWindow.outboundWindow
        tools.streamManager.initialFlowWindowChange(1) must_== Continue
        s.flowWindow.streamOutboundWindow must_== (startFlowWindow + 1)
      }

      "close streams flow window on a failed initial flow window change" in {
        // https://tools.ietf.org/html/rfc7540#section-6.9.2
        val tools = new MockTools(isClient = false)

        val Right(s) = tools.streamManager.newInboundStream(1)
        val delta = Int.MaxValue - s.flowWindow.outboundWindow
        s.flowWindow.streamOutboundAcked(delta) must beNone
        s.flowWindow.streamOutboundWindow must_== Int.MaxValue

        tools.streamManager.initialFlowWindowChange(1) must beLike {
          case Error(ex: Http2SessionException) => ex.code must_== FLOW_CONTROL_ERROR.code
        }

        "results in GOAWAY(PROTOCOL_ERROR) for update on idle stream" in {
          new MockTools(isClient = true).streamManager
            .flowWindowUpdate(1, 1) must beLike { case Error(ex: Http2SessionException) =>
            ex.code must_== PROTOCOL_ERROR.code
          }
        }
      }

      "handle successful flow window updates for streams" in {
        val tools = new MockTools(isClient = false)

        val Right(s) = tools.streamManager.newInboundStream(1)
        val initFlowWindow = s.flowWindow.outboundWindow
        tools.streamManager.flowWindowUpdate(streamId = 1, sizeIncrement = 1) must_== Continue
        s.flowWindow.streamOutboundWindow must_== (initFlowWindow + 1)
      }

      "handle failed flow window updates for streams" in {
        val tools = new MockTools(isClient = false)

        val Right(s) = tools.streamManager.newInboundStream(1)
        s.flowWindow.streamOutboundAcked(
          Int.MaxValue - s.flowWindow.streamOutboundWindow) must beNone

        tools.streamManager.flowWindowUpdate(streamId = 1, sizeIncrement = 1) must beLike {
          case Error(ex: Http2StreamException) => ex.code must_== FLOW_CONTROL_ERROR.code
        }
      }

      "handle successful flow window updates for the session" in {
        var sessionAcked: Option[Int] = None
        val tools = new MockTools(true) {
          override lazy val sessionFlowControl: SessionFlowControl = new MockSessionFlowControl {
            override def sessionOutboundAcked(count: Int): Option[Http2Exception] = {
              sessionAcked = Some(count)
              None
            }
          }
        }

        tools.streamManager.flowWindowUpdate(0, 100) must_== Continue
        sessionAcked must beSome(100)
      }

      "handle failed flow window updates for the session" in {
        var sessionAcked: Option[Int] = None
        val tools = new MockTools(true) {
          override lazy val sessionFlowControl: SessionFlowControl = new MockSessionFlowControl {
            override def sessionOutboundAcked(count: Int): Option[Http2Exception] = {
              sessionAcked = Some(count)
              Some(FLOW_CONTROL_ERROR.goaway("boom"))
            }
          }
        }

        tools.streamManager.flowWindowUpdate(0, 100) must beLike {
          case Error(ex: Http2SessionException) => ex.code must_== FLOW_CONTROL_ERROR.code
        }
        sessionAcked must beSome(100)
      }
    }

    "PUSH_PROMISE frames are rejected by default by the client" in {
      val tools = new MockTools(isClient = true)

      tools.streamManager
        .newOutboundStream()
        .writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty))

      tools.streamManager.handlePushPromise(
        streamId = 1,
        promisedId = 2,
        headers = Seq.empty) must beLike { case Error(ex: Http2StreamException) =>
        ex.code must_== REFUSED_STREAM.code
        ex.stream must_== 2
      }
    }

    "PUSH_PROMISE frames are rejected by the server" in {
      val tools = new MockTools(isClient = false)

      tools.streamManager.handlePushPromise(1, 2, Seq.empty) must beLike {
        case Error(ex: Http2SessionException) => ex.code must_== PROTOCOL_ERROR.code
      }
    }

    "PUSH_PROMISE with idle associated stream" in {
      val tools = new MockTools(isClient = true)

      tools.streamManager.handlePushPromise(1, 2, Seq.empty) must beLike {
        case Error(ex: Http2SessionException) => ex.code must_== PROTOCOL_ERROR.code
      }
    }

    "PUSH_PROMISE with closed associated stream" in {
      val tools = new MockTools(isClient = true)
      val streamId = tools.idManager.takeOutboundId().getOrElse(sys.error("failed to acquire id"))

      // We don't accept the stream because we don't support it, but it should
      // just be a RST_STREAM(REFUSED_STREAM) response
      tools.streamManager.handlePushPromise(streamId, 2, Seq.empty) must beLike {
        case Error(ex: Http2StreamException) =>
          ex.code must_== REFUSED_STREAM.code
          ex.stream must_== 2
      }
    }

    "PUSH_PROMISE with promised stream which is not idle" in {
      val promisedId = 2
      val tools = new MockTools(isClient = true)
      val streamId = tools.idManager.takeOutboundId().getOrElse(sys.error("failed to acquire id"))
      tools.idManager.observeInboundId(promisedId)

      tools.streamManager.handlePushPromise(streamId, promisedId, Seq.empty) must beLike {
        case Error(ex: Http2SessionException) => ex.code must_== PROTOCOL_ERROR.code
      }
    }
  }
}
