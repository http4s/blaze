package org.http4s.blaze.http

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.util.HeaderNames
import org.http4s.blaze.util.Execution

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/** Post routing response generator */
trait RouteAction {
  /** Generate a HTTP response using the passed continuation
    *
    * @param commit function which commits the response prelude and provides an appropriate [[BodyWriter]]
    * @tparam Writer the type of the [[BodyWriter]] with a refined `Finished` type
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
  def Streaming(code: Int, status: String, headers: Headers)
               (body: => Future[ByteBuffer])
               (implicit ec: ExecutionContext = Execution.trampoline): RouteAction =
    new RouteAction {
      override def handle[T <: BodyWriter](responder: (HttpResponsePrelude) => T): Future[T#Finished] = {
        val writer = responder(HttpResponsePrelude(code, status, headers))

//        // Not safe to do in scala 2.11 or lower :(
//        def go_(): Future[T#Finished] = body().flatMap {
//          case buff if buff.hasRemaining => writer.write(buff).flatMap(_ => go())
//          case _ => writer.close()
//        }

        val p = Promise[T#Finished]

        // Have to do this nonsense because a recursive Future loop isn't safe until scala 2.12+
        def go(): Unit = body.onComplete {
          case Failure(t) => p.tryFailure(t)
          case Success(buff) =>
            if (!buff.hasRemaining) p.completeWith(writer.close())
            else writer.write(buff).onComplete {
              case Success(_) => go()
              case Failure(t) => p.tryFailure(t)
            }
        }

        // Start our loop in the EC
        ec.prepare().execute(new Runnable { def run(): Unit = go() })

        p.future
      }
    }

  /** generate a HTTP response from a single `ByteBuffer`
    *
    * @param body the single `ByteBuffer` which represents the body.
    *
    * Note: this method will not modify the passed `ByteBuffer`, instead making
    * read-only views of it when writing to the socket, so the resulting responses
    * can be reused multiple times.
    */
  def Buffer(code: Int, status: String, body: ByteBuffer, headers: Headers): RouteAction =
    new RouteAction {
      override def handle[T <: BodyWriter](responder: (HttpResponsePrelude) => T): Future[T#Finished] = {
        val finalHeaders = (HeaderNames.ContentLength, body.remaining().toString) +: headers
        val prelude = HttpResponsePrelude(code, status, finalHeaders)
        val writer = responder(prelude)

        writer.write(body.asReadOnlyBuffer()).flatMap(_ => writer.close())(Execution.directec)
      }
    }

  /** generate a HTTP response from a String */
  def String(body: String, code: Int, status: String, headers: Headers): RouteAction =
    Buffer(code, status, StandardCharsets.UTF_8.encode(body), Utf8StringHeader +: headers)

  /** Generate a 200 OK HTTP response from an `Array[Byte]` */
  def Ok(body: Array[Byte], headers: Headers = Nil): RouteAction =
    Buffer(200, "OK", ByteBuffer.wrap(body), headers)

  /** Generate a 200 OK HTTP response from an `Array[Byte]` */
  def Ok(body: Array[Byte]): RouteAction = Ok(body, Nil)

  /** Generate a 200 OK HTTP response from a `String` */
  def Ok(body: String, headers: Headers): RouteAction =
    Ok(body.getBytes(StandardCharsets.UTF_8), Utf8StringHeader +: headers)

  /** Generate a 200 OK HTTP response from a `String` */
  def Ok(body: String): RouteAction = Ok(body, Nil)

  /** Generate a 200 OK HTTP response from a `ByteBuffer` */
  def Ok(body: ByteBuffer, headers: Headers): RouteAction =
    Buffer(200, "OK", body, headers)

  /** Generate a 200 OK HTTP response from a `ByteBuffer` */
  def Ok(body: ByteBuffer): RouteAction =
    Ok(body, Nil)

  /** Generate a 413 'Entity Too Large' response */
  def EntityTooLarge(): RouteAction =
    String(s"Request Entity Too Large", 413, "Request Entity Too Large", Nil)

  /** Generate a 500 'Internal Server Error' with the specified message */
  def InternalServerError(msg: String = "Internal Server Error", headers: Headers = Nil): RouteAction =
    String(msg, 500, "Internal Server Error", headers)

  private val Utf8StringHeader = "content-type" -> "text/plain; charset=utf-8"
}