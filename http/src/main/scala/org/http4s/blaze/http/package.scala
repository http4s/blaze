package org.http4s.blaze

import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.websocket.WebsocketBits.WebSocketFrame
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.charset.{Charset, StandardCharsets}

import org.http4s.blaze.util.Execution

import scala.concurrent.Future
import scala.xml.Node

package object http {

  type Headers = Seq[(String, String)]
  type Uri = String
  type Method = String

  sealed trait BodyCommand

  object BodyCommand {
    case object Next extends BodyCommand
    case object Kill extends BodyCommand
  }

  type Body = (BodyCommand) => Future[ByteBuffer]

  // The basic type that represents a HTTP service
  type HttpService = (Method, Uri, Headers, ByteBuffer) => Future[Response]

  sealed trait Response

  case class WSResponse(stage: LeafBuilder[WebSocketFrame]) extends Response

  case class HttpResponse(code: Int, status: String, headers: Headers, body: Body) extends Response {
    def stringBody(charset: Charset = UTF_8): Future[String] = {
      val acc = new StringBuilder

      def go(): Future[String] = body(BodyCommand.Next).flatMap { buffer =>
        if (buffer.hasRemaining()) {
          acc.append(charset.decode(buffer).toString)
          go()
        }
        else Future.successful(acc.result())
      }(Execution.trampoline)

      go()
    }
  }

  object HttpResponse {
    def apply(code: Int, status: String, headers: Headers, body: String): HttpResponse =
      apply(code, status, ("content-type", "text/plain; charset=utf-8")+:headers, StandardCharsets.UTF_8.encode(body))

    def apply(code: Int, status: String, headers: Headers, body: ByteBuffer): HttpResponse = {
      val _body = Future.successful(body)
      HttpResponse(code, status, headers, (_) => _body)
    }

    def Ok(body: Array[Byte], headers: Headers = Nil): HttpResponse =
      apply(200, "OK", headers, ByteBuffer.wrap(body))

    def Ok(body: String, headers: Headers): HttpResponse =
      Ok(body.getBytes(UTF_8), ("content-type", "text/plain; charset=utf-8")+:headers)

    def Ok(body: String): HttpResponse = Ok(body, Nil)

    def Ok(body: Node, headers: Headers): HttpResponse =
      Ok(body.toString(), headers)

    def Ok(body: Node): HttpResponse = Ok(body, Nil)

    def EntityTooLarge(): HttpResponse =
      HttpResponse(413, "Request Entity Too Large", Nil, s"Request Entity Too Large")
  }
}
