package org.http4s.blaze.http

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.http_parser.ResponseParser
import org.http4s.blaze.pipeline.stages.GatheringSeqHead
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.util.{BufferTools, Execution}
import org.specs2.mutable.Specification

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class HttpServerStageSpec extends Specification {

  private implicit def ec = Execution.trampoline

  private def renderRequests(requests: HttpRequest*): Seq[ByteBuffer] = {
    val acc = new ListBuffer[ByteBuffer]

    @tailrec
    def go(requests: List[HttpRequest]): Unit = requests match {
      case Nil => // break loop
      case h::t =>
        val s = s"${h.method} ${h.uri} HTTP/1.1\r\n"
        val hs = h.headers.foldLeft(s){ (acc, h) => acc + s"${h._1}: ${h._2}\r\n"}

        acc += StandardCharsets.UTF_8.encode(hs + (if (t.isEmpty) "connection: close\r\n\r\n" else "\r\n"))

        val body = h.body

        // gather the message body
        @tailrec
        def getBuffers(): Unit = {
          val buff = Await.result(body(), 10.seconds)
          if (buff.hasRemaining()) {
            acc += buff
            getBuffers()
          }
        }
        getBuffers()
        go(t)
    }

    go(requests.to[List])
    acc.result()
  }

  private def newStage(): HttpServerStage = {
    new HttpServerStage(Long.MaxValue, Int.MaxValue, ec)(service)
  }

  private def runPipeline(requests: HttpRequest*): ByteBuffer = {
    val leaf = newStage()
    val head = new GatheringSeqHead[ByteBuffer](renderRequests(requests:_*))
    LeafBuilder(leaf).base(head)

    BufferTools.joinBuffers(Await.result(head.go(), 10.seconds))
  }

  private def service(request: HttpRequest): Future[ResponseBuilder] = {
    request.uri match {
      case _ if request.method == "POST" =>
        request.body.accumulate().map { body =>
          val bodyStr = StandardCharsets.UTF_8.decode(body)
          RouteAction.Ok(s"Body: $bodyStr")
        }

      case "/ping" => Future.successful(RouteAction.Ok("ping response"))
      case "/pong" => Future.successful(RouteAction.Ok("pong response"))
    }
  }

  "HttpServerStage" should {
    "respond to a simple ping" in {
      val request = HttpRequest("GET", "/ping", Nil, MessageBody.emptyMessageBody)

      val resp = runPipeline(request)
      val (code, hs, body) = ResponseParser(resp)
      code must_== 200
      body must_== "ping response"
      hs.toSet must_== Set("connection" -> "close", "Content-Length" -> "13")
    }

    "run two requests" in {
      val request1 = HttpRequest("GET", "/ping", Nil, MessageBody.emptyMessageBody)
      val request2 = HttpRequest("GET", "/pong", Nil, MessageBody.emptyMessageBody)

      val resp = runPipeline(request1, request2)

      { // first response
        val (code, hs, body) = ResponseParser(resp)

        code must_== 200
        body must_== "ping response"
        hs.toSet must_== Set("Content-Length" -> "13")
      }

      { // second response
        val (code, hs, body) = ResponseParser(resp)

        code must_== 200
        body must_== "pong response"
        hs.toSet must_== Set("connection" -> "close", "Content-Length" -> "13")
      }
    }

    "run a request with a body" in {
      val b = StandardCharsets.UTF_8.encode("data")
      val req = HttpRequest("POST", "/foo", Seq("content-length" -> "4"), MessageBody(b))

      val resp = runPipeline(req)

      val (code, hs, body) = ResponseParser(resp)
      code must_== 200
      body must_== "Body: data"
      hs.toSet must_== Set("connection" -> "close", "Content-Length" -> "10")
    }
  }

}
