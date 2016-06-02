package org.http4s.blaze.http

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.util.Execution

import scala.concurrent.Future
import scala.xml.Node


object ResponseBuilder {
  private type Responder = (HttpResponsePrelude => BodyWriter) => Future[Completed]

    def apply(code: Int, status: String, headers: Headers, body: String): Responder =
      apply(code, status, ("content-type", "text/plain; charset=utf-8")+:headers, StandardCharsets.UTF_8.encode(body))

    def apply(code: Int, status: String, headers: Headers, body: ByteBuffer): Responder = f => {
      val prelude = HttpResponsePrelude(code, status, headers)
      val writer = f(prelude)
      writer.write(body).flatMap(_ => writer.close())(Execution.directec)
    }

    def Ok(body: Array[Byte], headers: Headers = Nil): Responder =
      apply(200, "OK", headers, ByteBuffer.wrap(body))

    def Ok(body: String, headers: Headers): Responder =
      Ok(body.getBytes(StandardCharsets.UTF_8), ("content-type", "text/plain; charset=utf-8")+:headers)

    def Ok(body: String): Responder = Ok(body, Nil)

    def Ok(body: Node, headers: Headers): Responder =
      Ok(body.toString(), headers)

    def Ok(body: Node): Responder = Ok(body, Nil)

    def EntityTooLarge(): Responder =
      ResponseBuilder(413, "Request Entity Too Large", Nil, s"Request Entity Too Large")
}
