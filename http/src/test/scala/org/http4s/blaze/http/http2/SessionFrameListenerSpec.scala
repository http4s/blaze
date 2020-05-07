package org.http4s.blaze.http.http2

import java.nio.charset.StandardCharsets
import scala.language.reflectiveCalls
import org.http4s.blaze.http.Headers
import org.http4s.blaze.http.http2.Http2Exception._
import org.http4s.blaze.http.http2.mocks.MockStreamManager
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.pipeline.stages.BasicTail
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification
import scala.util.Success

class SessionFrameListenerSpec extends Specification {
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

  "SessionFrameListener" >> {
    "on HEADERS frame" >> {
      "use an existing stream" >> {
        val tools: MockTools = new MockTools(isClient = true)
        val os = tools.streamManager.newOutboundStream()

        // initialize the stream
        os.writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty))

        tools.frameListener.onCompleteHeadersFrame(
          streamId = os.streamId,
          priority = Priority.NoPriority,
          endStream = false,
          headers = hs)

        os.readRequest(1).value must beLike {
          case Some(Success(HeadersFrame(Priority.NoPriority, false, hss))) => hs must_== hss
        }
      }

      "initiate a new stream for idle inbound stream (server)" >> {
        val head = new BasicTail[StreamFrame]("")
        val tools = new MockTools(isClient = false) {
          override lazy val newInboundStream = Some { _: Int => LeafBuilder(head) }
        }

        tools.streamManager.get(1) must beNone

        tools.frameListener.onCompleteHeadersFrame(
          streamId = 1,
          priority = Priority.NoPriority,
          endStream = false,
          headers = hs)

        tools.streamManager.get(1) must beSome
        head.channelRead().value must beLike {
          case Some(Success(HeadersFrame(Priority.NoPriority, false, hss))) => hs must_== hss
        }
      }
    }

    "on PUSH_PROMISE frame" >> {
      "server receive push promise" >> {
        val tools = new MockTools(isClient = false)

        tools.frameListener.onCompletePushPromiseFrame(2, 1, Seq.empty) must beLike {
          case Error(ex: Http2SessionException) => ex.code must_== PROTOCOL_ERROR.code
        }
      }

      "push promise disabled" >> {
        val tools = new MockTools(isClient = true)
        tools.localSettings.pushEnabled = false

        val os = tools.streamManager.newOutboundStream()
        os.writeRequest(HeadersFrame(Priority.NoPriority, false, Seq.empty)) // initiate stream

        tools.frameListener.onCompletePushPromiseFrame(
          os.streamId,
          os.streamId + 1,
          hs) must beLike {
          case Error(ex: Http2SessionException) => ex.code must_== PROTOCOL_ERROR.code
        }
      }

      "delegates to StreamManager" >> {
        val tools = new MockTools(isClient = true) {
          var sId, pId = -1
          var hss: Headers = Nil
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

        tools.frameListener.onCompletePushPromiseFrame(1, 2, hs) must_== Continue
        tools.sId must_== 1
        tools.pId must_== 2
        tools.hss must_== hs
      }
    }

    "on DATA frame" >> {
      "passes it to open streams" >> {
        val tools = new MockTools(isClient = true)

        val os = tools.streamManager.newOutboundStream()
        os.writeRequest(HeadersFrame(Priority.NoPriority, false, Seq.empty)) // initiate stream

        val data = BufferTools.allocate(4)
        tools.frameListener.onDataFrame(os.streamId, true, data, 4) must_== Continue

        os.readRequest(1).value must beLike {
          case Some(Success(DataFrame(true, d))) => d must_== data
        }
      }

      "Update session flow bytes as consumed for closed streams" >> {
        val tools = new MockTools(isClient = true)

        val os = tools.streamManager.newOutboundStream()
        os.writeRequest(HeadersFrame(Priority.NoPriority, false, Seq.empty)) // initiate stream
        os.doCloseWithError(None)

        val data = BufferTools.allocate(4)

        val init = tools.sessionFlowControl.sessionInboundWindow
        tools.frameListener.onDataFrame(os.streamId, true, data, 4) must beLike {
          case Error(ex: Http2StreamException) => ex.code must_== STREAM_CLOSED.code
        }

        tools.sessionFlowControl.sessionInboundWindow must_== init - 4
      }

      "results in GOAWAY(PROTOCOL_ERROR) for idle streams" >> {
        val tools = new MockTools(isClient = true)

        val init = tools.sessionFlowControl.sessionInboundWindow
        tools.frameListener.onDataFrame(1, true, BufferTools.emptyBuffer, 4) must beLike {
          case Error(ex: Http2SessionException) => ex.code must_== PROTOCOL_ERROR.code
        }

        tools.sessionFlowControl.sessionInboundWindow must_== init - 4
      }

      "sends RST_STREAM(STREAM_CLOSED) for closed streams" >> {
        val tools = new MockTools(isClient = true)

        val os = tools.streamManager.newOutboundStream()
        os.writeRequest(HeadersFrame(Priority.NoPriority, false, Seq.empty)) // initiate stream
        os.doCloseWithError(None)

        val data = BufferTools.allocate(4)

        tools.frameListener.onDataFrame(os.streamId, true, data, 4) must beLike {
          case Error(ex: Http2StreamException) => ex.code must_== STREAM_CLOSED.code
        }
      }
    }

    "on RST_STREAM" >> {
      "delegates to the StreamManager" >> {
        val tools = new MockTools(true) {
          var observedCause: Option[Http2StreamException] = None
          override lazy val streamManager = new MockStreamManager() {
            override def rstStream(cause: Http2StreamException) = {
              observedCause = Some(cause)
              Continue
            }
          }
        }

        tools.frameListener.onRstStreamFrame(1, STREAM_CLOSED.code) must_== Continue
        tools.observedCause must beLike {
          case Some(ex) =>
            ex.stream must_== 1
            ex.code must_== STREAM_CLOSED.code
        }
      }
    }

    "on WINDOW_UPDATE" >> {
      "delegates to the StreamManager" >> {
        val tools = new MockTools(true) {
          var observedIncrement: Option[(Int, Int)] = None
          override lazy val streamManager = new MockStreamManager() {
            override def flowWindowUpdate(streamId: Int, sizeIncrement: Int) = {
              observedIncrement = Some(streamId -> sizeIncrement)
              Continue
            }
          }
        }

        tools.frameListener.onWindowUpdateFrame(1, 2) must_== Continue
        tools.observedIncrement must beLike {
          case Some((1, 2)) => ok
        }
      }
    }

    "on PING frame" >> {
      "writes ACK's for peer initiated PINGs" >> {
        val tools = new MockTools(true)
        val data = (0 until 8).map(_.toByte).toArray
        tools.frameListener.onPingFrame(false, data) must_== Continue

        val written = tools.writeController.observedWrites.dequeue()
        written must_== FrameSerializer.mkPingFrame(ack = true, data = data)
      }

      "pass ping ACK's to the PingManager" >> {
        val tools = new MockTools(true) {
          var observedAck: Option[Array[Byte]] = None
          override lazy val pingManager = new PingManager(this) {
            override def pingAckReceived(data: Array[Byte]): Unit =
              observedAck = Some(data)
          }
        }
        val data = (0 until 8).map(_.toByte).toArray
        tools.frameListener.onPingFrame(true, data) must_== Continue
        tools.observedAck must_== Some(data)
      }
    }

    "on SETTINGS frame" >> {
      "Updates remote settings" >> {
        val tools = new MockTools(true)
        val settingChange = Http2Settings.INITIAL_WINDOW_SIZE(1)
        tools.frameListener.onSettingsFrame(Some(Seq(settingChange))) must_== Continue

        // Should have changed the setting
        tools.remoteSettings.initialWindowSize must_== 1

        // Should have written an ACK
        val written = tools.writeController.observedWrites.dequeue()
        written must_== FrameSerializer.mkSettingsAckFrame()
      }
    }

    "on GOAWAY frame" >> {
      "delegates to the sessions goAway logic" >> {
        val tools = new MockTools(true) {
          var observedGoAway: Option[(Int, Http2SessionException)] = None
          override def invokeGoAway(
              lastHandledOutboundStream: Int,
              reason: Http2SessionException
          ): Unit =
            observedGoAway = Some(lastHandledOutboundStream -> reason)
        }

        tools.frameListener.onGoAwayFrame(
          1,
          NO_ERROR.code,
          "lol".getBytes(StandardCharsets.UTF_8)) must_== Continue

        tools.observedGoAway must beLike {
          case Some((1, Http2SessionException(NO_ERROR.code, "lol"))) => ok
        }
      }
    }
  }
}
