/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.HttpClientSession
import org.http4s.blaze.http.http2.ProtocolFrame.GoAway
import org.http4s.blaze.http.http2.mocks.MockByteBufferHeadStage
import org.http4s.blaze.pipeline.stages.BasicTail
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.util.{BufferTools, Execution}
import org.specs2.mutable.Specification

import scala.concurrent.duration._
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

class ConnectionImplSpec extends Specification {
  private class Ctx {
    lazy val head = new MockByteBufferHeadStage
    lazy val tailStage = new BasicTail[ByteBuffer]("Tail")

    // Zip up the stages
    LeafBuilder(tailStage).base(head)

    lazy val localSettings: Http2Settings = Http2Settings.default

    lazy val remoteSettings: MutableHttp2Settings = MutableHttp2Settings.default()

    lazy val flowStrategy = new DefaultFlowStrategy(localSettings)

    lazy val streamBuilder: Option[Int => LeafBuilder[StreamFrame]] = None

    lazy val connection = new ConnectionImpl(
      tailStage = tailStage,
      localSettings = localSettings,
      remoteSettings = remoteSettings,
      flowStrategy = flowStrategy,
      inboundStreamBuilder = streamBuilder,
      parentExecutor = Execution.trampoline
    )

    def decodeGoAway(data: ByteBuffer): ProtocolFrame =
      ProtocolFrameDecoder.decode(data)
  }

  "ConnectionImpl" >> {
    "quality" >> {
      "be 0.0 when the core is closed" in {
        val ctx = new Ctx
        import ctx._

        val f = connection.drainSession(Duration.Zero)
        while (head.writes.nonEmpty) head.writes.dequeue()._2.success(())

        CodecUtils.await(f)
        connection.quality must_== 0.0
      }

      "be 0.0 when there are no available streams" in {
        val ctx = new Ctx
        import ctx._

        remoteSettings.maxConcurrentStreams = 0
        connection.quality must_== 0.0
      }

      "be 0.0 if draining" in {
        val ctx = new Ctx
        import ctx._

        connection.drainSession(Duration.Inf)
        connection.quality must_== 0.0
      }

      "compute a value based on the number of available streams" in {
        val ctx = new Ctx
        import ctx._

        remoteSettings.maxConcurrentStreams = 2
        connection.quality must_== 1.0

        // need to use the stream before it actually counts
        val stream = connection.newOutboundStream()
        val tail = new BasicTail[StreamFrame]("tail")
        LeafBuilder(tail).base(stream)
        tail.channelWrite(HeadersFrame(Priority.NoPriority, true, Seq.empty))

        connection.quality must_== 0.5
      }
    }

    "status" >> {
      "Open if the session can dispatch" in {
        val ctx = new Ctx
        import ctx._
        connection.status must_== HttpClientSession.Ready
      }

      "Busy if the quality is 0.0" in {
        val ctx = new Ctx
        import ctx._

        remoteSettings.maxConcurrentStreams = 0
        connection.status must_== HttpClientSession.Busy
      }

      "Busy when the session is draining" in {
        val ctx = new Ctx
        import ctx._

        connection.drainSession(Duration.Inf)
        connection.status must_== HttpClientSession.Busy
      }

      "Closed if the core is closed" in {
        val ctx = new Ctx
        import ctx._

        val f = connection.drainSession(Duration.Zero)
        while (head.writes.nonEmpty) head.writes.dequeue()._2.success(())

        CodecUtils.await(f)
        connection.status must_== HttpClientSession.Closed
      }
    }

    "invokeShutdownWithError" >> {
      "is idempotent" in {
        val ctx = new Ctx
        import ctx._

        connection.invokeShutdownWithError(None, "test1")

        decodeGoAway(head.consumeOutboundByteBuf()) must beLike {
          case GoAway(0, Http2SessionException(code, _)) =>
            code must_== Http2Exception.NO_ERROR.code
        }

        connection.invokeShutdownWithError(None, "test2")
        head.writes.isEmpty must beTrue
      }

      "sends a GOAWAY frame if no GOAWAY has been sent" in {
        val ctx = new Ctx
        import ctx._

        connection.invokeShutdownWithError(None, "test1")

        decodeGoAway(head.consumeOutboundByteBuf()) must beLike {
          case GoAway(0, Http2SessionException(code, _)) =>
            code must_== Http2Exception.NO_ERROR.code
        }
      }

      "won't send a GOAWAY frame if a GOAWAY has been sent" in {
        val ctx = new Ctx
        import ctx._

        connection.drainSession(Duration.Inf)

        decodeGoAway(head.consumeOutboundByteBuf()) must beLike {
          case GoAway(0, Http2SessionException(code, _)) =>
            code must_== Http2Exception.NO_ERROR.code
        }

        connection.invokeShutdownWithError(None, "test1")

        head.consumeOutboundByteBuf().remaining must_== 0
      }
    }

    "invokeGoAway" >> {
      "immediately closes streams below the last handled stream" in {
        val ctx = new Ctx
        import ctx._

        val stage = connection.newOutboundStream()
        val basicStage = new BasicTail[StreamFrame]("")
        LeafBuilder(basicStage).base(stage)

        val w1 =
          basicStage.channelWrite(HeadersFrame(Priority.NoPriority, endStream = true, Seq.empty))
        head.consumeOutboundByteBuf()
        w1.value must_== Some(Success(()))
        // Now the stream has been initiated
        val err = Http2Exception.NO_ERROR.goaway("")
        connection.invokeGoAway(math.max(0, connection.idManager.lastOutboundStream - 2), err)
        head.consumeOutboundByteBuf() // need to consume the GOAWAY

        val w2 = basicStage.channelRead()
        w2.value must beLike { case Some(Failure(e: Http2StreamException)) =>
          e.code must_== Http2Exception.REFUSED_STREAM.code
        }
      }

      "lets non-terminated streams finish" in {
        val ctx = new Ctx
        import ctx._

        val stage = connection.newOutboundStream()
        val basicStage = new BasicTail[StreamFrame]("")
        LeafBuilder(basicStage).base(stage)

        val w1 =
          basicStage.channelWrite(HeadersFrame(Priority.NoPriority, endStream = false, Seq.empty))
        head.consumeOutboundByteBuf()
        w1.value must_== Some(Success(()))
        // Now the stream has been initiated
        val err = Http2Exception.NO_ERROR.goaway("")
        connection.invokeGoAway(connection.idManager.lastOutboundStream, err)
        head.consumeOutboundByteBuf() // need to consume the GOAWAY

        val w2 = basicStage.channelWrite(DataFrame(true, BufferTools.emptyBuffer))
        w2.value must_== Some(Success(()))
      }

      "rejects new streams" in {
        val ctx = new Ctx
        import ctx._

        connection.invokeDrain(4.seconds)

        val stage = connection.newOutboundStream()
        val basicStage = new BasicTail[StreamFrame]("")
        LeafBuilder(basicStage).base(stage)
        val f = basicStage.channelWrite(HeadersFrame(Priority.NoPriority, true, Seq.empty))
        f.value must beLike { case Some(Failure(t: Http2StreamException)) =>
          t.code must_== Http2Exception.REFUSED_STREAM.code
        }
      }
    }

    "invokeDrain" >> {
      "sends a GOAWAY" in {
        val ctx = new Ctx
        import ctx._

        connection.invokeDrain(4.seconds)

        decodeGoAway(head.consumeOutboundByteBuf()) must beLike {
          case GoAway(0, Http2SessionException(code, _)) =>
            code must_== Http2Exception.NO_ERROR.code
        }
      }

      "lets existing streams finish" in {
        val ctx = new Ctx
        import ctx._

        val stage = connection.newOutboundStream()
        val basicStage = new BasicTail[StreamFrame]("")
        LeafBuilder(basicStage).base(stage)

        val w1 =
          basicStage.channelWrite(HeadersFrame(Priority.NoPriority, endStream = false, Seq.empty))
        head.consumeOutboundByteBuf()
        w1.value must_== Some(Success(()))
        // Now the stream has been initiated
        connection.invokeDrain(4.seconds)
        head.consumeOutboundByteBuf() // need to consume the GOAWAY

        val w2 = basicStage.channelWrite(DataFrame(true, BufferTools.emptyBuffer))
        w2.value must_== Some(Success(()))
      }

      "rejects new streams" in {
        val ctx = new Ctx
        import ctx._

        connection.invokeDrain(4.seconds)

        val stage = connection.newOutboundStream()
        val basicStage = new BasicTail[StreamFrame]("")
        LeafBuilder(basicStage).base(stage)
        val f = basicStage.channelWrite(HeadersFrame(Priority.NoPriority, true, Seq.empty))
        f.value must beLike { case Some(Failure(t: Http2StreamException)) =>
          t.code must_== Http2Exception.REFUSED_STREAM.code
        }
      }
    }
  }
}
