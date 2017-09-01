package org.http4s.blaze.http.http2

import org.http4s.blaze.pipeline.Command.Disconnect
import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage}
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

class Http2StreamStateSpec extends Specification with Http2SpecTools {

  private def newTools: Http2MockTools = new Http2MockTools(true)

  private def makePipeline(head: Http2StreamState): TailStage[StreamMessage] = {
    val t = new TailStage[StreamMessage] {
      override def name: String = "MockTail"
    }
    LeafBuilder(t).base(head)
    t
  }

  "Http2StreamState" >> {
    "inboundData" >> {
      "queue multiple frames" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)
        val tail = makePipeline(stage)

        stage.invokeInboundHeaders(None, false /* EOS */, Nil) must_== Continue
        stage.invokeInboundData(true, BufferTools.emptyBuffer, 0) must_== Continue

        // messages
        await(tail.channelRead(1)) must_== HeadersFrame(None, false, Nil)
        await(tail.channelRead(1)) must_== DataFrame(true, BufferTools.emptyBuffer)
        await(tail.channelRead(1)) must throwA[Command.EOF.type]
      }

      "Flow window overflow" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)
        stage.flowWindow.inboundObserved(stage.flowWindow.streamInboundWindow) // both depleted
        stage.flowWindow.streamInboundWindow must_== 0
        tools.flowControl.sessionInboundWindow must_== 0
        stage.invokeInboundData(false, BufferTools.emptyBuffer, 10) must beLike(connectionError(Http2Exception.FLOW_CONTROL_ERROR))

        // windows shouldn't have changed
        stage.flowWindow.streamInboundWindow must_== 0
        tools.flowControl.sessionInboundWindow must_== 0

        tools.flowControl.sessionInboundAcked(10) // only stream depleted
        stage.flowWindow.streamInboundWindow must_== 0
        tools.flowControl.sessionInboundWindow must_== 10
        stage.invokeInboundData(false, BufferTools.emptyBuffer, 10) must beLike(connectionError(Http2Exception.FLOW_CONTROL_ERROR))

        stage.flowWindow.streamInboundAcked(20) // only session depleted
        stage.flowWindow.inboundObserved(10) must beTrue
        stage.flowWindow.streamInboundWindow must_== 10
        tools.flowControl.sessionInboundWindow must_== 0
        stage.invokeInboundData(false, BufferTools.emptyBuffer, 10) must beLike(connectionError(Http2Exception.FLOW_CONTROL_ERROR))

        tools.flowControl.sessionInboundAcked(10) // both have 10 bytes available
        stage.flowWindow.streamInboundWindow must_== 10
        tools.flowControl.sessionInboundWindow must_== 10
        stage.invokeInboundData(false, BufferTools.emptyBuffer, 10) must_== Continue

        stage.flowWindow.streamInboundWindow must_== 0
        tools.flowControl.sessionInboundWindow must_== 0
      }

      "HEADERS frame after EOS is a connection PROTOCOL_ERROR" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)

        stage.invokeInboundHeaders(None, true, Nil) must_== Continue
        stage.invokeInboundHeaders(None, false, Nil) must beLike(connectionError(Http2Exception.STREAM_CLOSED))
      }

      "DATA frame after EOS is a connection PROTOCOL_ERROR" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)

        stage.invokeInboundHeaders(None, true, Nil) must_== Continue
        stage.invokeInboundData(false, BufferTools.emptyBuffer, 0) must beLike(connectionError(Http2Exception.STREAM_CLOSED))
      }

      "consume inbound bytes" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)
        val tail = makePipeline(stage)

        val initialInbound = stage.flowWindow.streamInboundWindow

        // unconsumed bytes should be 0 since no data consisted of the flow bytes
        stage.invokeInboundData(false, BufferTools.emptyBuffer, 10) must_== Continue
        stage.flowWindow.streamUnconsumedBytes must_== 0
        tools.flowControl.sessionUnconsumedBytes must_== 0
        stage.flowWindow.streamInboundWindow must_== initialInbound - 10

        stage.invokeInboundData(false, zeroBuffer(10), 10) must_== Continue
        stage.flowWindow.streamUnconsumedBytes must_== 10
        tools.flowControl.sessionUnconsumedBytes must_== 10
        stage.flowWindow.streamInboundWindow must_== initialInbound - 20

        // We read the first buffer
        await(tail.channelRead(1)) must_== DataFrame(false, BufferTools.emptyBuffer)
        stage.flowWindow.streamUnconsumedBytes must_== 10
        tools.flowControl.sessionUnconsumedBytes must_== 10

        // and now the second buffer
        await(tail.channelRead(1)) must_== DataFrame(false, zeroBuffer(10))
        stage.flowWindow.streamUnconsumedBytes must_== 0
        tools.flowControl.sessionUnconsumedBytes must_== 0
      }

      "channelRead on receivedEndStream" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)
        val tail = makePipeline(stage)


        stage.invokeInboundHeaders(None, endStream = true, Nil) must_== Continue

        // messages
        await(tail.channelRead(1)) must_== HeadersFrame(None, true, Nil)
        await(tail.channelRead(1)) must throwA[Command.EOF.type]
      }

      "channelRead on stream closed event" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)
        val tail = makePipeline(stage)
        tail.sendOutboundCommand(Disconnect)

        // messages
        await(tail.channelRead(1)) must throwA[Command.EOF.type]
      }
    }

    "outbound data" >> {
      "register interests on HeadersFrame" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)

        val f = stage.writeRequest(HeadersFrame(None, true, Nil))
        tools.writeListener.observedInterests.result must_== stage::Nil
      }

      "register interests on HeadersFrame with depleted stream flow window" >> {
        val tools = newTools
        tools.flowControl.sessionOutboundAcked(100)
        val stage = new MockHttp2StreamState(1, tools)
        stage.flowWindow.outboundRequest(stage.flowWindow.streamOutboundWindow)
        tools.flowControl.sessionOutboundWindow must_== 100
        stage.flowWindow.streamOutboundWindow must_== 0

        val f = stage.writeRequest(HeadersFrame(None, true, Nil))
        tools.writeListener.observedInterests.result must_== stage::Nil
      }

      "register interests on HeadersFrame with depleted session flow window" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)
        stage.flowWindow.outboundRequest(stage.flowWindow.streamOutboundWindow)
        stage.flowWindow.streamOutboundAcked(100)
        tools.flowControl.sessionOutboundWindow must_== 0
        stage.flowWindow.streamOutboundWindow must_== 100

        val f = stage.writeRequest(HeadersFrame(None, true, Nil))
        tools.writeListener.observedInterests.result must_== stage::Nil
      }

      "register interests on DataFrame with sufficient flow" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)

        val f = stage.writeRequest(DataFrame(true, BufferTools.emptyBuffer))
        tools.writeListener.observedInterests.result must_== stage::Nil
      }

      "register interests on zero byte DataFrame with depleted flow windows" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)
        stage.flowWindow.outboundRequest(stage.flowWindow.streamOutboundWindow)
        tools.flowControl.sessionOutboundWindow must_== 0
        stage.flowWindow.streamOutboundWindow must_== 0

        val f = stage.writeRequest(DataFrame(true, BufferTools.emptyBuffer))
        tools.writeListener.observedInterests.result must_== stage::Nil
      }

      "not register interests on DataFrame with depleted stream flow window" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)
        tools.flowControl.sessionOutboundAcked(10)
        stage.flowWindow.outboundRequest(stage.flowWindow.streamOutboundWindow)
        tools.flowControl.sessionOutboundWindow must_== 10
        stage.flowWindow.streamOutboundWindow must_== 0

        val f = stage.writeRequest(DataFrame(true, zeroBuffer(10)))
        tools.writeListener.observedInterests.result must_== Nil
      }

      "not register interests on DataFrame with depleted session flow window" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)
        stage.flowWindow.outboundRequest(stage.flowWindow.streamOutboundWindow)
        stage.flowWindow.streamOutboundAcked(10)
        tools.flowControl.sessionOutboundWindow must_== 0
        stage.flowWindow.streamOutboundWindow must_== 10

        val f = stage.writeRequest(DataFrame(true, zeroBuffer(10)))
        tools.writeListener.observedInterests.result must_== Nil
      }
    }

    "stream state" >> {
      "Receive EOS" >> {
        "on first HEADERS" >> {
          val tools = newTools
          val stage = new MockHttp2StreamState(1, tools)
          val tail = makePipeline(stage)

          stage.invokeInboundHeaders(None, true /* EOS */, Nil) must_== Continue

          // first message
          await(tail.channelRead(1)) must_== HeadersFrame(None, true, Nil)
          await(tail.channelRead(1)) must throwA[Command.EOF.type]
        }

        "on first DATA" >> {
          val tools = newTools
          val stage = new MockHttp2StreamState(1, tools)
          val tail = makePipeline(stage)

          stage.invokeInboundData(true, BufferTools.emptyBuffer, 0) must_== Continue

          // first message
          await(tail.channelRead(1)) must_== DataFrame(true, BufferTools.emptyBuffer)
          await(tail.channelRead(1)) must throwA[Command.EOF.type]
        }

        "on second frame (DATA)" >> {
          val tools = newTools
          val stage = new MockHttp2StreamState(1, tools)
          val tail = makePipeline(stage)

          stage.invokeInboundHeaders(None, false /* EOS */, Nil) must_== Continue
          stage.invokeInboundData(true, BufferTools.emptyBuffer, 0) must_== Continue

          // messages
          await(tail.channelRead(1)) must_== HeadersFrame(None, false, Nil)
          await(tail.channelRead(1)) must_== DataFrame(true, BufferTools.emptyBuffer)
          await(tail.channelRead(1)) must throwA[Command.EOF.type]
        }
      }

      "send EOS" >> {
        "raise an IllegalStateException for writes after EOS received" >> {
          val tools = newTools
          val stage = new MockHttp2StreamState(1, tools)
          val tail = makePipeline(stage)

          val w1 = tail.channelWrite(DataFrame(true, BufferTools.emptyBuffer))
          val _ = stage.performStreamWrite()
          await(w1)

          val w2 = tail.channelWrite(DataFrame(true, BufferTools.emptyBuffer))
          await(w2) must throwA[IllegalStateException]
        }
      }

      "send RST_STREAM if disconnected in open state" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)
        val tail = makePipeline(stage)
        tail.sendOutboundCommand(Command.Disconnect)

        stage.onStreamFinishedResult must beLike {
          case Some(Some(ex: Http2StreamException)) => ex.code must_== Http2Exception.CANCEL.code
        }
      }

      "send RST_STREAM if disconnected in half closed (remote) state on Disconnect command" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)
        val tail = makePipeline(stage)
        stage.invokeInboundHeaders(None, true, Nil) must_== Continue
        tail.sendOutboundCommand(Command.Disconnect)

        stage.onStreamFinishedResult must beLike {
          case Some(Some(ex: Http2StreamException)) => ex.code must_== Http2Exception.CANCEL.code
        }
      }

      "send RST_STREAM if disconnected in half closed (local) state on Disconnect command" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)
        val tail = makePipeline(stage)
        tail.channelWrite(HeadersFrame(None, true, Nil))
        stage.performStreamWrite().isEmpty must beFalse // actually wrote the data
        tail.sendOutboundCommand(Command.Disconnect)

        stage.onStreamFinishedResult must beLike {
          case Some(Some(ex: Http2StreamException)) => ex.code must_== Http2Exception.CANCEL.code
        }
      }

      "NOT send RST_STREAM if disconnected in closed state on Disconnect command" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)
        val tail = makePipeline(stage)

        stage.invokeInboundHeaders(None, true, Nil) must_== Continue
        tail.channelWrite(HeadersFrame(None, true, Nil))

        stage.performStreamWrite().isEmpty must beFalse // actually wrote the data
        tail.sendOutboundCommand(Command.Disconnect)

        stage.onStreamFinishedResult must beSome(None)
      }

      "forward Http2Exceptions send as Error commands by pipeline" >> {
        val tools = newTools
        val stage = new MockHttp2StreamState(1, tools)
        val tail = makePipeline(stage)

        tail.sendOutboundCommand(Command.Error(Http2Exception.CANCEL.rst(-1)))

        stage.onStreamFinishedResult must beLike {
          case Some(Some(ex: Http2StreamException)) =>
            ex.code must_== Http2Exception.CANCEL.code
            ex.stream must_== 1 // this should have been added bye the Http2StreamState
        }
      }

      "only call `onStreamFinished` once even with multiple close events" >> {
        val tools = newTools
        var onStreamFinishedCount = 0
        val stage = new MockHttp2StreamState(1, tools) {
          override protected def onStreamFinished(ex: Option[Http2Exception]): Unit = {
            onStreamFinishedCount += 1
            super.onStreamFinished(ex)
          }
        }
        val tail = makePipeline(stage)

        onStreamFinishedCount must_== 0

        tail.sendOutboundCommand(Command.Disconnect)

        onStreamFinishedCount must_== 1

        tail.sendOutboundCommand(Command.Disconnect)
        tail.sendOutboundCommand(Command.Error(Http2Exception.CANCEL.rst(-1)))

        onStreamFinishedCount must_== 1
      }
    }
  } // Http2StreamState
}
