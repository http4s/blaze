package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.http2._
import org.http4s.blaze.http.http2.mocks.MockHeadStage
import org.http4s.blaze.http._
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.http4s.blaze.util.{BufferTools, Execution}
import org.specs2.mutable.Specification

import scala.collection.mutable
import scala.concurrent.Future

class ServerStageSpec extends Specification {

  private implicit def ex = Execution.trampoline

  private val goodHeaders = Seq(
    ":method" -> "GET",
    ":scheme" -> "https",
    ":path" -> "/",
    "other" -> "funny header"
  )

  private class Ctx {

    lazy val service: HttpService = { _ =>
      Future.successful(RouteAction.Ok("foo"))
    }

    lazy val config = HttpServerStageConfig()

    lazy val head = new MockHeadStage[StreamMessage]

    lazy val stage: ServerStage = new ServerStage(1, service, config)

    def connect(): Unit = {
      LeafBuilder(stage).base(head)
      head.sendInboundCommand(Command.Connected)
    }
  }

  "ServerStage" >> {

    "fails to start with a non-headers frame" >> {
      val ctx = new Ctx
      import ctx._

      connect()
      head.reads.dequeue().success(DataFrame(false, BufferTools.emptyBuffer))
      head.error must beLike {
        case Some(ex: Http2SessionException) => ex.code must_== Http2Exception.PROTOCOL_ERROR.code
      }
    }

    "generates an empty body if the first frame has EOS" >> {
      val ctx = new Ctx {
        var hadBody: Option[Boolean] = None
        override lazy val service: HttpService = { req =>
          hadBody = Some(!req.body.isExhausted)
          Future.successful(RouteAction.Ok("cool"))
        }
      }
      import ctx._

      connect()
      head.reads.dequeue().success(HeadersFrame(Priority.NoPriority, true, goodHeaders))
      hadBody must beSome(false)
    }

    "generates a body" >> {
      val ctx = new Ctx {
        val serviceReads = new mutable.Queue[ByteBuffer]
        override lazy val service: HttpService = { req =>
          for {
            b1 <- req.body()
            b2 <- req.body()
            b3 <- req.body()
          } yield {
            serviceReads += b1
            serviceReads += b2
            serviceReads += b3
            RouteAction.Ok("cool")
          }
        }
      }
      import ctx._

      connect()
      head.reads.dequeue().success(HeadersFrame(Priority.NoPriority, false, goodHeaders))
      head.reads.dequeue().success(DataFrame(false, BufferTools.allocate(1)))
      head.reads.dequeue().success(DataFrame(true, BufferTools.allocate(2)))

      serviceReads.toList must_== List(BufferTools.allocate(1), BufferTools.allocate(2), BufferTools.emptyBuffer)
    }

    "The service can write the body" >> {
      val ctx = new Ctx
      import ctx._

      connect()
      head.reads.dequeue().success(HeadersFrame(Priority.NoPriority, true, goodHeaders))
      val (HeadersFrame(_, false, hs), p) = head.writes.dequeue()
      val hsMap = hs.toMap
      hsMap(":status") must_== "200"
      hsMap("content-length") must_== "3"
      p.success(())

      val (DataFrame(false, data), p2) = head.writes.dequeue()
      data must_== StandardCharsets.UTF_8.encode("foo")
    }

    "finishing the action disconnects the stage" >> {
      val ctx = new Ctx
      import ctx._

      connect()
      head.reads.dequeue().success(HeadersFrame(Priority.NoPriority, true, goodHeaders))
      val (HeadersFrame(_, false, _), p1) = head.writes.dequeue()
      p1.success(())

      val (DataFrame(false, _), p2) = head.writes.dequeue()
      p2.success(())

      val (DataFrame(true, data), p3) = head.writes.dequeue()
      p3.success(())

      data.remaining must_== 0
      head.disconnected must beTrue
    }

    "failing the action disconnects with an error" >> {
      val ex = new Exception("sadface")
      val ctx = new Ctx {
        override lazy val service: HttpService = { _ =>
          Future.failed(ex)
        }
      }
      import ctx._

      connect()
      head.reads.dequeue().success(HeadersFrame(Priority.NoPriority, true, goodHeaders))

      head.error must beSome(ex)
    }

    "head requests get a noop writer" >> {
      val ctx = new Ctx
      import ctx._

      val headHeaders = Seq(
        ":method" -> "HEAD",
        ":scheme" -> "https",
        ":path" -> "/",
        "other" -> "funny header"
      )

      connect()
      head.reads.dequeue().success(HeadersFrame(Priority.NoPriority, true, headHeaders))
      val (HeadersFrame(_, true, hs), p) = head.writes.dequeue()
      val hsMap = hs.toMap
      hsMap(":status") must_== "200"
      hsMap("content-length") must_== "3"
      p.success(())

      head.disconnected must beTrue
    }
  }

}
