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

import java.nio.ByteBuffer

import org.http4s.blaze.http.HttpClientSession
import org.http4s.blaze.http.http2.ProtocolFrame.GoAway
import org.http4s.blaze.http.http2.mocks.MockByteBufferHeadStage
import org.http4s.blaze.pipeline.stages.BasicTail
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.testkit.BlazeTestSuite
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.concurrent.duration._
import scala.concurrent.duration.Duration
import scala.concurrent.Future
import scala.util.{Failure, Success}

class ConnectionImplSuite extends BlazeTestSuite {
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

  test("A ConnectionImpl.quality should be 0.0 when the core is closed") {
    val ctx = new Ctx
    import ctx._

    val f = connection.drainSession(Duration.Zero)
    while (head.writes.nonEmpty) head.writes.dequeue()._2.success(())

    for {
      _ <- f
      _ <- assertFuture(Future(connection.quality), 0.0)
    } yield ()
  }

  test("A ConnectionImpl.quality should be 0.0 when there are no available streams") {
    val ctx = new Ctx
    import ctx._

    remoteSettings.maxConcurrentStreams = 0
    assertEquals(connection.quality, 0.0)
  }

  test("A ConnectionImpl.quality should be 0.0 if draining") {
    val ctx = new Ctx
    import ctx._

    connection.drainSession(Duration.Inf)
    assertEquals(connection.quality, 0.0)
  }

  test("A ConnectionImpl.quality should compute a value based on the number of available streams") {
    val ctx = new Ctx
    import ctx._

    remoteSettings.maxConcurrentStreams = 2
    assertEquals(connection.quality, 1.0)

    // need to use the stream before it actually counts
    val stream = connection.newOutboundStream()
    val tail = new BasicTail[StreamFrame]("tail")
    LeafBuilder(tail).base(stream)
    tail.channelWrite(HeadersFrame(Priority.NoPriority, true, Seq.empty))

    assertEquals(connection.quality, 0.5)
  }

  test("A ConnectionImpl.status should open if the session can dispatch") {
    val ctx = new Ctx
    import ctx._
    assertEquals(connection.status, HttpClientSession.Ready)
  }

  test("A ConnectionImpl.status should be busy if the quality is 0.0") {
    val ctx = new Ctx
    import ctx._

    remoteSettings.maxConcurrentStreams = 0
    assertEquals(connection.status, HttpClientSession.Busy)
  }

  test("A ConnectionImpl.status should be busy when the session is draining") {
    val ctx = new Ctx
    import ctx._

    connection.drainSession(Duration.Inf)
    assertEquals(connection.status, HttpClientSession.Busy)
  }

  test("A ConnectionImpl.status should be closed if the core is closed") {
    val ctx = new Ctx
    import ctx._

    val f = connection.drainSession(Duration.Zero)
    while (head.writes.nonEmpty) head.writes.dequeue()._2.success(())

    for {
      _ <- f
      _ <- assertFuture(Future(connection.status), HttpClientSession.Closed)
    } yield ()
  }

  test("A ConnectionImpl.invokeShutdownWithError is idempotent ") {
    val ctx = new Ctx
    import ctx._

    connection.invokeShutdownWithError(None, "test1")

    decodeGoAway(head.consumeOutboundByteBuf()) match {
      case GoAway(0, Http2SessionException(code, _)) =>
        assertEquals(code, Http2Exception.NO_ERROR.code)

      case _ =>
        fail("Unexpected ProtocolFrame found")
    }

    connection.invokeShutdownWithError(None, "test2")
    assert(head.writes.isEmpty)
  }

  test("A ConnectionImpl.invokeShutdownWithError sends a GOAWAY frame if no GOAWAY has been sent") {
    val ctx = new Ctx
    import ctx._

    connection.invokeShutdownWithError(None, "test1")

    decodeGoAway(head.consumeOutboundByteBuf()) match {
      case GoAway(0, Http2SessionException(code, _)) =>
        assertEquals(code, Http2Exception.NO_ERROR.code)

      case _ =>
        fail("Unexpected decodeGoAway result found")
    }
  }

  test(
    "A ConnectionImpl.invokeShutdownWithError won't send a GOAWAY frame if a GOAWAY has been sent") {
    val ctx = new Ctx
    import ctx._

    connection.drainSession(Duration.Inf)

    decodeGoAway(head.consumeOutboundByteBuf()) match {
      case GoAway(0, Http2SessionException(code, _)) =>
        assertEquals(code, Http2Exception.NO_ERROR.code)

      case _ =>
        fail("Unexpected ProtocolFrame found")
    }

    connection.invokeShutdownWithError(None, "test1")

    assertEquals(head.consumeOutboundByteBuf().remaining, 0)
  }

  test("A ConnectionImpl.invokeGoAway immediately closes streams below the last handled stream") {
    val ctx = new Ctx
    import ctx._

    val stage = connection.newOutboundStream()
    val basicStage = new BasicTail[StreamFrame]("")
    LeafBuilder(basicStage).base(stage)

    val w1 =
      basicStage.channelWrite(HeadersFrame(Priority.NoPriority, endStream = true, Seq.empty))
    head.consumeOutboundByteBuf()

    assertEquals(w1.value, Some(Success(())))

    // Now the stream has been initiated
    val err = Http2Exception.NO_ERROR.goaway("")
    connection.invokeGoAway(math.max(0, connection.idManager.lastOutboundStream - 2), err)
    head.consumeOutboundByteBuf() // need to consume the GOAWAY

    val w2 = basicStage.channelRead()
    w2.value match {
      case Some(Failure(e: Http2StreamException)) =>
        assertEquals(e.code, Http2Exception.REFUSED_STREAM.code)

      case _ =>
        fail("Unexpected channelRead result found")
    }
  }

  test("A ConnectionImpl.invokeGoAway lets non-terminated streams finish") {
    val ctx = new Ctx
    import ctx._

    val stage = connection.newOutboundStream()
    val basicStage = new BasicTail[StreamFrame]("")
    LeafBuilder(basicStage).base(stage)

    val w1 =
      basicStage.channelWrite(HeadersFrame(Priority.NoPriority, endStream = false, Seq.empty))
    head.consumeOutboundByteBuf()

    assertEquals(w1.value, Some(Success(())))

    // Now the stream has been initiated
    val err = Http2Exception.NO_ERROR.goaway("")
    connection.invokeGoAway(connection.idManager.lastOutboundStream, err)
    head.consumeOutboundByteBuf() // need to consume the GOAWAY

    val w2 = basicStage.channelWrite(DataFrame(true, BufferTools.emptyBuffer))
    assertEquals(w2.value, Some(Success(())))
  }

  test("A ConnectionImpl.invokeGoAway rejects new streams") {
    val ctx = new Ctx
    import ctx._

    connection.invokeDrain(4.seconds)

    val stage = connection.newOutboundStream()
    val basicStage = new BasicTail[StreamFrame]("")
    LeafBuilder(basicStage).base(stage)
    val f = basicStage.channelWrite(HeadersFrame(Priority.NoPriority, true, Seq.empty))

    f.value match {
      case Some(Failure(t: Http2StreamException)) =>
        assertEquals(t.code, Http2Exception.REFUSED_STREAM.code)

      case _ =>
        fail("Unexpected channelWrite result found")
    }
  }

  test("A ConnectionImpl.invokeDrain sends a GOAWAY") {
    val ctx = new Ctx
    import ctx._

    connection.invokeDrain(4.seconds)

    decodeGoAway(head.consumeOutboundByteBuf()) match {
      case GoAway(0, Http2SessionException(code, _)) =>
        assertEquals(code, Http2Exception.NO_ERROR.code)

      case _ =>
        fail("Unexpected decodeGoAway result found")
    }
  }

  test("A ConnectionImpl.invokeDrain lets existing streams finish") {
    val ctx = new Ctx
    import ctx._

    val stage = connection.newOutboundStream()
    val basicStage = new BasicTail[StreamFrame]("")
    LeafBuilder(basicStage).base(stage)

    val w1 =
      basicStage.channelWrite(HeadersFrame(Priority.NoPriority, endStream = false, Seq.empty))
    head.consumeOutboundByteBuf()

    assertEquals(w1.value, Some(Success(())))

    // Now the stream has been initiated
    connection.invokeDrain(4.seconds)
    head.consumeOutboundByteBuf() // need to consume the GOAWAY

    val w2 = basicStage.channelWrite(DataFrame(true, BufferTools.emptyBuffer))
    assertEquals(w2.value, Some(Success(())))
  }

  test("A ConnectionImpl.invokeDrain rejects new streams") {
    val ctx = new Ctx
    import ctx._

    connection.invokeDrain(4.seconds)

    val stage = connection.newOutboundStream()
    val basicStage = new BasicTail[StreamFrame]("")
    LeafBuilder(basicStage).base(stage)
    val f = basicStage.channelWrite(HeadersFrame(Priority.NoPriority, true, Seq.empty))
    f.value match {
      case Some(Failure(t: Http2StreamException)) =>
        assertEquals(t.code, Http2Exception.REFUSED_STREAM.code)

      case _ =>
        fail("Unexpected channelWrite result found")
    }
  }
}
