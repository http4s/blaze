package org.http4s.blaze

import java.nio.ByteBuffer
import java.nio.charset.Charset

import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.util.{BufferTools, Execution}
import org.http4s.websocket.WebsocketBits.WebSocketFrame

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future

package object http {

  type Headers = Seq[(String, String)]
  type Uri = String
  type Method = String

  // The basic type that represents a HTTP service
  type HttpService = Request => ResponseBuilder

  class Completed private[http](val result: http.HttpServerStage.RouteResult) extends AnyVal

  case class Request(method: Method, uri: Uri, headers: Headers, body: () => Future[ByteBuffer])

  case class HttpResponsePrelude(code: Int, status: String, headers: Headers)

  sealed trait ResponseBuilder

  case class WSResponseBuilder(stage: LeafBuilder[WebSocketFrame]) extends ResponseBuilder

  case class HttpResponseBuilder(handler: (HttpResponsePrelude => BodyWriter) => Future[Completed]) extends ResponseBuilder

  case class ClientResponse(code: Int, status: String, headers: Headers, body: () => Future[ByteBuffer]) {
    def stringBody(): Future[String] = {
      val acc = new ArrayBuffer[ByteBuffer](8)

      def go(): Future[String] = body().flatMap { buffer =>
        if (buffer.hasRemaining) {
          acc += buffer
          go()
        }
        else {
          val b = BufferTools.joinBuffers(acc)
          val encoding = headers.collectFirst {
            case (k, v) if k.equalsIgnoreCase("content-type") => "UTF-8" // TODO: this needs to examine the header
          }.getOrElse("UTF-8")

          try {
            val bodyString = Charset.forName(encoding).decode(b).toString()
            Future.successful(bodyString)
          }
          catch { case e: Throwable => Future.failed(e) }
        }
      }(Execution.trampoline)

      go()
    }
  }
}
