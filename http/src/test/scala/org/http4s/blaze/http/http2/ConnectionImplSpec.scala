package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.HttpClientSession
import org.http4s.blaze.http.http2.ProtocolFrame.GoAway
import org.http4s.blaze.http.http2.mocks.MockFrameListener
import org.http4s.blaze.pipeline.stages.BasicTail
import org.http4s.blaze.pipeline.{HeadStage, LeafBuilder}
import org.http4s.blaze.util.{BufferTools, Execution}
import org.specs2.mutable.Specification

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}

class ConnectionImplSpec extends Specification {

  private class Head extends HeadStage[ByteBuffer] {
    override def name: String = "Head"

    val reads = new mutable.Queue[Promise[ByteBuffer]]()
    val writes = new mutable.Queue[(ByteBuffer, Promise[Unit])]()

    override def readRequest(size: Int): Future[ByteBuffer] = {
      val p = Promise[ByteBuffer]
      reads += p
      p.future
    }

    override def writeRequest(data: ByteBuffer): Future[Unit] = {
      val p = Promise[Unit]
      writes += data -> p
      p.future
    }
  }

  private class Ctx {

    lazy val head = new Head
    lazy val tailStage = new BasicTail[ByteBuffer]("Tail")

    // Zip up the stages
    LeafBuilder(tailStage).base(head)

    lazy val localSettings: Http2Settings = Http2Settings.default

    lazy val remoteSettings: MutableHttp2Settings = MutableHttp2Settings.default()

    lazy val flowStrategy = new DefaultFlowStrategy(localSettings)

    lazy val streamBuilder: Option[Int => LeafBuilder[StreamMessage]] = None

    lazy val connection = new ConnectionImpl(
      tailStage = tailStage,
      localSettings = localSettings,
      remoteSettings = remoteSettings,
      flowStrategy = flowStrategy,
      inboundStreamBuilder = streamBuilder,
      parentExecutor = Execution.trampoline
    )
  }

  private def decodeGoAway(buffer: ByteBuffer): ProtocolFrame.GoAway = {
    var goAway: ProtocolFrame.GoAway = null
    val listener = new MockFrameListener(false) {
      override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: Array[Byte]): Result = {
        val str = new String(debugData, StandardCharsets.UTF_8)
        goAway = ProtocolFrame.GoAway(lastStream, Http2SessionException(errorCode, str))
        Continue
      }
    }
    require(new FrameDecoder(Http2Settings.default, listener).decodeBuffer(buffer) == Continue)
    require(goAway != null)
    goAway
  }

  "ConnectionImpl" >> {
    "quality" >> {
      "be 0.0 when the core is closed" in {
        val ctx = new Ctx
        import ctx._

        CodecUtils.await(connection.drainSession(Duration.Zero))
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
        val tail = new BasicTail[StreamMessage]("tail")
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

        CodecUtils.await(connection.drainSession(Duration.Zero))
        connection.status must_== HttpClientSession.Closed
      }
    }

    "invokeShutdownWithError" >> {
      "is idempotent" in {
        ko
      }

      "sends a GOAWAY frame if no GOAWAY has been sent" in {
        ko
      }

      "wont send a GOAWAY frame if a GOAWAY has been sent" in {
        ko
      }
    }

    "invokeGoAway" >> {
      "immediately closes streams below the last handled stream" in {
        ko
      }

      "lets non-terminated streams finish" in {
        ko
      }

      "rejects new streams" in {
        ko
      }
    }

    "invokeDrain" >> {
      "sends a GOAWAY" in {
        val ctx = new Ctx
        import ctx._

        connection.invokeDrain(4.seconds)
        val buf = BufferTools.joinBuffers(head.writes.toList.map(_._1))
        decodeGoAway(buf) must beLike {
          case GoAway(0, Http2SessionException(code, _)) => code must_== Http2Exception.NO_ERROR.code
        }
      }

      "lets existing streams finish" in {
        ko
      }

      "rejects new streams" in {
        ko
      }
    }
  }
}
