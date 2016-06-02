package org.http4s.blaze.http

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.util.Execution

import scala.concurrent.Future

/** Post routing response generator */
trait RouteAction {
  /** Generate a HTTP response using the passed continuation
    *
    * @param commit function which commits the response prelude and provides an appropriate [[BodyWriter]]
    * @tparam Writer the type of the [[BodyWriter]] with a refinded `Finished` type
    * @return an asynchronous `BodyWriter#Finished` object. This type enforces that the [[BodyWriter]]
    *         has been successfully closed.
    */
  def handle[Writer <: BodyWriter](commit: (HttpResponsePrelude => Writer)): Future[Writer#Finished]
}

object RouteAction {
  /** generate a streaming HTTP response
    *
    * @param body each invocation should generate the __next__ body chunk. Each chunk will be written
    *             before requesting another chunk. Termination is signaled by an __empty__ `ByteBuffer` as
    *             defined by the return value of `ByteBuffer.hasRemaining()`.
    */
  def streaming(code: Int, status: String, headers: Headers)(body: () => Future[ByteBuffer]): HttpResponse = HttpResponse(
    new RouteAction {
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
  )

  /** generate a HTTP response from a single `ByteBuffer`
    *
    * @param body the single `ByteBuffer` which represents the body.
    */
  def byteBuffer(code: Int, status: String, headers: Headers, body: ByteBuffer): HttpResponse = HttpResponse(
    new RouteAction {
      override def handle[T <: BodyWriter](responder: (HttpResponsePrelude) => T): Future[T#Finished] = {

        val prelude = HttpResponsePrelude(code, status, headers)
        val writer = responder(prelude)

        writer.write(body).flatMap(_ => writer.close())(Execution.directec)
      }
    }
  )

  /** generate a HTTP response from a String */
  def string(code: Int, status: String, headers: Headers, body: String): HttpResponse =
    byteBuffer(code, status, ("content-type", "text/plain; charset=utf-8")+:headers, StandardCharsets.UTF_8.encode(body))

  /** Generate a 200 OK HTTP response from an `Array[Byte]` */
  def Ok(body: Array[Byte], headers: Headers = Nil): HttpResponse =
    byteBuffer(200, "OK", headers, ByteBuffer.wrap(body))

  /** Generate a 200 OK HTTP response from a `String` */
  def Ok(body: String, headers: Headers): HttpResponse =
    Ok(body.getBytes(StandardCharsets.UTF_8), ("content-type", "text/plain; charset=utf-8")+:headers)

  /** Generate a 200 OK HTTP response from a `String` */
  def Ok(body: String): HttpResponse = Ok(body, Nil)

  def EntityTooLarge(): HttpResponse =
    string(413, "Request Entity Too Large", Nil, s"Request Entity Too Large")
}