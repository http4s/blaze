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
  type HttpService = Request => Response

  class Completed private[http](val result: http.HttpServerStage.RouteResult) extends AnyVal

  case class Request(method: Method, uri: Uri, headers: Headers, body: () => Future[ByteBuffer])

  case class HttpResponsePrelude(code: Int, status: String, headers: Headers)

  sealed trait Response

  case class WSResponse(stage: LeafBuilder[WebSocketFrame]) extends Response

  case class HttpResponse(handler: (HttpResponsePrelude => BodyWriter) => Future[Completed]) extends Response
}
