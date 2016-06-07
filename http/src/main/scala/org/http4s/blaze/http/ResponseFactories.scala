package org.http4s.blaze.http

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.util.Execution

import scala.concurrent.Future
import scala.xml.Node

// TODO: a typeclass pattern would be much better than this, but headers need to get better first.

trait ResponseFactories {

  def streaming(code: Int, status: String, headers: Headers)(body: () => Future[ByteBuffer]): RouteAction = new RouteAction {
    override def handle[T <: BodyWriter](responder: (HttpResponsePrelude) => T): Future[T#Finished] = {
      implicit val ec = Execution.trampoline
      val writer = responder(HttpResponsePrelude(code, status, headers))

      def go(): Future[T#Finished] = body().flatMap {
        case buff if buff.hasRemaining => writer.write(buff).flatMap(_ => go())
        case _ => writer.close()
      }

      go()
    }
  }

  def byteBuffer(code: Int, status: String, headers: Headers, body: ByteBuffer): RouteAction = new RouteAction {
    override def handle[T <: BodyWriter](responder: (HttpResponsePrelude) => T): Future[T#Finished] = {

      val prelude = HttpResponsePrelude(code, status, headers)
      val writer = responder(prelude)

      writer.write(body).flatMap(_ => writer.close())(Execution.directec)
    }
  }

  def string(code: Int, status: String, headers: Headers, body: String): RouteAction =
    byteBuffer(code, status, ("content-type", "text/plain; charset=utf-8")+:headers, StandardCharsets.UTF_8.encode(body))

  def Ok(body: Array[Byte], headers: Headers = Nil): RouteAction =
    byteBuffer(200, "OK", headers, ByteBuffer.wrap(body))

  def Ok(body: String, headers: Headers): RouteAction =
    Ok(body.getBytes(StandardCharsets.UTF_8), ("content-type", "text/plain; charset=utf-8")+:headers)

  def Ok(body: String): RouteAction = Ok(body, Nil)

  def Ok(body: Node, headers: Headers): RouteAction =
    Ok(body.toString(), headers)

  def Ok(body: Node): RouteAction = Ok(body, Nil)

  def EntityTooLarge(): RouteAction =
    string(413, "Request Entity Too Large", Nil, s"Request Entity Too Large")
}
