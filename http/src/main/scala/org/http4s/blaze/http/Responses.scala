package org.http4s.blaze.http

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.util.Execution

import scala.concurrent.Future
import scala.xml.Node


object Responses {

    def apply(code: Int, status: String, headers: Headers, body: String): HttpResponseBuilder =
      apply(code, status, ("content-type", "text/plain; charset=utf-8")+:headers, StandardCharsets.UTF_8.encode(body))

    def apply(code: Int, status: String, headers: Headers, body: ByteBuffer): HttpResponseBuilder = HttpResponseBuilder(new Cat {
      override def handle[T <: BodyWriter](responder: (HttpResponsePrelude) => T): Future[T#Finished] = {

        val prelude = HttpResponsePrelude(code, status, headers)
        val writer = responder(prelude)

        writer.write(body).flatMap(_ => writer.close())(Execution.directec)
      }
    })

    def Ok(body: Array[Byte], headers: Headers = Nil): HttpResponseBuilder =
      apply(200, "OK", headers, ByteBuffer.wrap(body))

    def Ok(body: String, headers: Headers): HttpResponseBuilder =
      Ok(body.getBytes(StandardCharsets.UTF_8), ("content-type", "text/plain; charset=utf-8")+:headers)

    def Ok(body: String): HttpResponseBuilder = Ok(body, Nil)

    def Ok(body: Node, headers: Headers): HttpResponseBuilder =
      Ok(body.toString(), headers)

    def Ok(body: Node): HttpResponseBuilder = Ok(body, Nil)

    def EntityTooLarge(): HttpResponseBuilder =
      apply(413, "Request Entity Too Large", Nil, s"Request Entity Too Large")
}
