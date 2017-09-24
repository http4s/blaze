package org.http4s.blaze.http.http2

import scala.language.reflectiveCalls
import java.nio.ByteBuffer

import org.http4s.blaze.http.Headers
import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.specs2.mutable.Specification

import scala.collection.mutable

class SessionFrameHandlerSpec extends Specification with Http2SpecTools {

  private class Ctx(isClient: Boolean) {
    lazy val tools = new Http2MockTools(isClient)

    lazy val activeStreams: mutable.HashMap[Int, MockHttp2StreamState] = new mutable.HashMap[Int, MockHttp2StreamState]()

    lazy val handler: SessionFrameListener[MockHttp2StreamState] = new MockSessionFrameListener

    class MockSessionFrameListener extends SessionFrameListener[MockHttp2StreamState](
      tools.mySettings,
      tools.headerDecoder,
      activeStreams,
      tools.flowControl,
      tools.idManager) {
      override protected def newInboundStream(streamId: Int): Option[MockHttp2StreamState] = ???
      override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result = ???
      override protected def handlePushPromise(streamId: Int, promisedId: Int, headers: Headers): Http2Result = ???
      override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = ???
      override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: Array[Byte]): Http2Result = ???
    }
  }

  "SessionFrameHandler" >> {

    "on HEADERS frame" >> {
      class HandlerCtx(isClient: Boolean) extends Ctx(isClient) {
        var observedStreamId: Option[Int] = None
        override lazy val handler = new MockSessionFrameListener {
          override protected def newInboundStream(streamId: Int): Option[MockHttp2StreamState] = {
            observedStreamId = Some(streamId)
            None
          }
        }
      }

      "result in a protocol for stream ID 0" >> {
        val ctx = new HandlerCtx(false)
        ctx.handler.onCompleteHeadersFrame(0, Priority.NoPriority, true, Nil) must beLike(ConnectionProtoError)
        ctx.observedStreamId must beNone
      }

      "result in a PROTOCOL_ERROR for idle outbound stream" >> {
        val ctx = new HandlerCtx(false)
        ctx.handler.onCompleteHeadersFrame(2, Priority.NoPriority, true, Nil) must beLike(ConnectionProtoError)
        ctx.observedStreamId must beNone
      }

      "result in a Http2StreamException with code STREAM_CLOSED for closed outbound stream" >> {
        val ctx = new HandlerCtx(false)
        val Some(id) = ctx.tools.idManager.takeOutboundId()
        ctx.handler.onCompleteHeadersFrame(id, Priority.NoPriority, true, Nil) must beLike {
          case Error(err: Http2SessionException) =>
            err.code must_== Http2Exception.PROTOCOL_ERROR.code
        }
        ctx.observedStreamId must beNone
      }

      "initiate a new stream for idle inbound stream (server)" >> {
        val ctx = new HandlerCtx(false)
        ctx.handler.onCompleteHeadersFrame(1, Priority.NoPriority, true, Nil) must beLike { case Error(err) =>
          err.code must_== Http2Exception.REFUSED_STREAM.code
        }
        ctx.observedStreamId must beSome(1)
        ctx.tools.idManager.lastInboundStream must_== 1
      }
    }

    "on PUSH_PROMISE frame" >> {
      case class PushPromise(streamId: Int, promisedId: Int, headers: Headers)
      class HandlerCtx(isClient: Boolean) extends Ctx(isClient) {
        var pushPromiseResult: Option[PushPromise] = None
        override lazy val handler = new MockSessionFrameListener {
          override protected def handlePushPromise(streamId: Int, promisedId: Int, headers: Headers): Http2Result = {
            pushPromiseResult = Some(PushPromise(streamId, promisedId, headers))
            Continue
          }
        }
      }

      "connection PROTOCOL_ERROR" >> {
        "disabled by settings results" >> {
          val ctx = new HandlerCtx(true)
          ctx.tools.mySettings.pushEnabled = false
          ctx.handler.onCompletePushPromiseFrame(1, 2, Nil) must beLike(ConnectionProtoError)
        }

        "associated stream is idle" >> {
          val ctx = new HandlerCtx(true)
          ctx.handler.onCompletePushPromiseFrame(1, 2, Nil) must beLike(ConnectionProtoError)
        }

        "promised id is not in idle state" >> {
          val ctx = new HandlerCtx(true)
          val Some(id) = ctx.tools.idManager.takeOutboundId()
          ctx.tools.idManager.observeInboundId(id + 1)
          ctx.handler.onCompletePushPromiseFrame(id, id + 1, Nil) must beLike(ConnectionProtoError)
        }

        "received by server" >> {
          val ctx = new HandlerCtx(isClient = false)
          val Some(id) = ctx.tools.idManager.takeOutboundId()
          ctx.handler.onCompletePushPromiseFrame(id, id + 1, Nil) must beLike(ConnectionProtoError)
        }
      }

      "accept for stream in open state" >> {
        val ctx = new HandlerCtx(true)
        val Some(id) = ctx.tools.idManager.takeOutboundId()
        ctx.handler.onCompletePushPromiseFrame(id, id + 1, Nil) must_== Continue
        ctx.pushPromiseResult must beSome(PushPromise(id, id + 1, Nil))
      }
    }

    "on DATA frame" >> {
      def bytes(i: Int): ByteBuffer = ByteBuffer.wrap(new Array(i))
      val FlowControlError = connectionError(Http2Exception.FLOW_CONTROL_ERROR)

      "connection PROTOCOL_ERROR" >> {
        "idle stream" >> {
          val ctx = new Ctx(true)
          ctx.handler.onDataFrame(1, true, bytes(10), 10) must beLike(ConnectionProtoError)
          ctx.handler.onDataFrame(2, true, bytes(10), 10) must beLike(ConnectionProtoError)
        }

        "session flow window overflow" >> {
          val ctx = new Ctx(true)
          ctx.tools.idManager.observeInboundId(1)
          assert(ctx.tools.flowControl.sessionInboundObserved(ctx.tools.flowControl.sessionInboundWindow))
          ctx.handler.onDataFrame(1, true, bytes(10), 10) must beLike(FlowControlError)
        }

        "stream flow window overflow" >> {
          val ctx = new Ctx(true)
          ctx.tools.idManager.observeInboundId(2)
          ctx.activeStreams.put(2, ctx.tools.newStream(2))
          ctx.tools.flowControl.sessionInboundAcked(100) // give some space in the session
          val w = ctx.activeStreams(2).flowWindow
          assert(w.inboundObserved(w.streamInboundWindow))
          assert(w.streamInboundWindow == 0)
          ctx.handler.onDataFrame(2, true, bytes(10), 10) must beLike(FlowControlError)
        }
      }

      "stream STREAM_CLOSED on closed stream" >> {
        "session flow window overflow" >> {
          val ctx = new Ctx(true)
          ctx.tools.idManager.observeInboundId(2)
          val w = ctx.tools.flowControl.sessionInboundWindow
          ctx.handler.onDataFrame(2, true, bytes(10), 10) must beLike {
            case Error(e: Http2StreamException) => e.code must_== Http2Exception.STREAM_CLOSED.code
          }
          // Still count the flow bytes against the session
          ctx.tools.flowControl.sessionInboundWindow must_== w - 10
        }
      }

      "give data to a open stream with available flow window" >> {
        val ctx = new Ctx(true)
        ctx.tools.idManager.observeInboundId(2)
        val stream = ctx.tools.newStream(2)
        ctx.activeStreams.put(2, stream)
        val sessionWindow = ctx.tools.flowControl.sessionInboundWindow

        val w = ctx.activeStreams(2).flowWindow
        val streamWindow = w.streamInboundWindow
        ctx.handler.onDataFrame(2, true, bytes(10), 10) must_== Continue

        ctx.tools.flowControl.sessionInboundWindow must_== sessionWindow - 10
        w.streamInboundWindow must_== streamWindow - 10
      }
    }

    "on RST_STREAM" >> {
      "idle stream is connection PROTOCOL_ERROR" >> {
        val ctx = new Ctx(true)
        ctx.handler.onRstStreamFrame(1, 1) must beLike(ConnectionProtoError)
      }

      "closed stream is ok" >> {
        val ctx = new Ctx(true)
        val id = 2
        ctx.tools.idManager.observeInboundId(id)
        ctx.handler.onRstStreamFrame(id, 1 /*code*/) must_== Continue
      }

      "open stream is removed" >> {
        val ctx = new Ctx(true)
        val id = 2
        ctx.tools.idManager.observeInboundId(id)
        val mockStream = ctx.tools.newStream(id)
        ctx.activeStreams.put(id, mockStream)
        ctx.handler.onRstStreamFrame(id, 1 /*code*/) must_== Continue // don't reply with a RST_STREAM to a RST_STREAM
        ctx.activeStreams.get(id) must beNone
        mockStream.onStreamFinishedResult must beLike { case Some(Some(e: Http2StreamException)) => e.code must_== 1 }
      }
    }

    "on WINDOW_UPDATE" >> {
      val startFlowWindow = new Ctx(true).tools.mySettings.initialWindowSize
      "connection PROTOCOL_ERROR on idle stream update stream" >> {
        val ctx = new Ctx(true)
        ctx.handler.onWindowUpdateFrame(1 /*streamid*/, 1 /*increment*/) must beLike(ConnectionProtoError)
        ctx.tools.flowControl.sessionOutboundWindow must_== startFlowWindow
        ctx.activeStreams.isEmpty must beTrue
      }

      "invalid increment" >> {
        Set(-1, 0).foreach { inc =>
          val ctx = new Ctx(true)
          ctx.tools.idManager.observeInboundId(2)
          ctx.handler.onWindowUpdateFrame(0 /*streamid*/, inc /*increment*/) must beLike(ConnectionProtoError)
          ctx.handler.onWindowUpdateFrame(2 /*streamid*/, inc /*increment*/) must beLike {
            case Error(ex: Http2StreamException) =>
              ex.code must_== Http2Exception.FLOW_CONTROL_ERROR.code
              ex.stream must_== 2
          }
        }
        ok
      }

      "invalid increment on open stream" >> {
        val ctx = new Ctx(true)
        ctx.tools.idManager.observeInboundId(2)
        val stream = ctx.tools.newStream(2)
        ctx.activeStreams.put(2, stream)
        val result = ctx.handler.onWindowUpdateFrame(2 /*streamid*/, 0 /*increment*/)
        result must beLike {
          case Error(ex: Http2StreamException) =>
            ex.code must_== Http2Exception.FLOW_CONTROL_ERROR.code
            ex.stream must_== 2
        }

        stream.onStreamFinishedResult must beLike {
          case Some(Some(ex: Http2StreamException)) =>
            ex.code must_== Http2Exception.FLOW_CONTROL_ERROR.code
            ex.stream must_== 2
        }
        // call to `streamFinished(Some(ex))` is responsible for removing the stream from the live set,
        // but the mock implementation doesn't do that
        ctx.activeStreams.get(2) must beSome(stream)
      }

      "connection flow update" >> {
        val ctx = new Ctx(true)
        ctx.tools.idManager.observeInboundId(2)
        ctx.activeStreams.put(2, ctx.tools.newStream(2))
        ctx.handler.onWindowUpdateFrame(0 /*streamid*/, 1 /*increment*/) must_== Continue
        ctx.tools.flowControl.sessionOutboundWindow must_== startFlowWindow + 1
        ctx.activeStreams(2).outboundFlowAcks must_== 1 // make sure the streams were notified

      }

      "stream flow update" >> {
        val ctx = new Ctx(true)
        ctx.tools.idManager.observeInboundId(2)
        val stream = ctx.tools.newStream(2)
        ctx.activeStreams.put(2, stream)
        ctx.handler.onWindowUpdateFrame(2 /*streamid*/, 1 /*increment*/) must_== Continue
        stream.flowWindow.streamOutboundWindow must_== startFlowWindow + 1
        ctx.activeStreams(2).outboundFlowAcks must_== 1  // make sure the stream was notified
      }

    }
  }
}
