package org.http4s.blaze

import java.nio.ByteBuffer
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.websocket.WebsocketBits.WebSocketFrame

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.charset.{StandardCharsets, Charset}

import scala.concurrent.Future
import scala.xml.Node

package object http {

  type Headers = Seq[(String, String)]
  type Uri = String
  type Method = String

  // The basic type that represents a HTTP service
  type HttpService = (Method, Uri, Headers, ByteBuffer) => Future[Response]

  sealed trait Response

  case class WSResponse(stage: LeafBuilder[WebSocketFrame]) extends Response

  case class HttpResponse(code: Int, status: String, headers: Headers, body: ByteBuffer) extends Response {
    def stringBody(charset: Charset = UTF_8): String = {
      // In principle we should get the charset from the headers
      charset.decode(body.asReadOnlyBuffer()).toString
    }
  }

  object HttpResponse {
    def apply(code: Int, status: String = "", headers: Headers = Nil, body: String = ""): HttpResponse =
      HttpResponse(code, status, ("content-type", "text/plain; charset=utf-8")+:headers, StandardCharsets.UTF_8.encode(body))

    def Ok(body: Array[Byte], headers: Headers = Nil): HttpResponse =
      HttpResponse(200, "OK", headers, ByteBuffer.wrap(body))

    def Ok(body: String, headers: Headers): HttpResponse =
      Ok(body.getBytes(UTF_8), ("content-type", "text/plain; charset=utf-8")+:headers)

    def Ok(body: String): HttpResponse = Ok(body, Nil)

    def Ok(body: Node, headers: Headers): HttpResponse =
      Ok(body.toString(), headers)

    def Ok(body: Node): HttpResponse = Ok(body, Nil)

    def EntityTooLarge(): HttpResponse =
      HttpResponse(413, "Request Entity Too Large", body = s"Request Entity Too Large")
  }
}
