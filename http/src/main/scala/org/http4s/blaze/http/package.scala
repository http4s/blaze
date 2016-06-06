package org.http4s.blaze

import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.websocket.WebsocketBits.WebSocketFrame

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

  trait Cat {
    def handle[T <: BodyWriter](responder: (HttpResponsePrelude => T)): Future[T#Finished]
  }

  case class HttpResponseBuilder(handler: Cat) extends ResponseBuilder
}
