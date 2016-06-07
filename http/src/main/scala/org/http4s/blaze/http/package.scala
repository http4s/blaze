package org.http4s.blaze

import java.nio.ByteBuffer

import scala.language.existentials

import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.websocket.WebsocketBits.WebSocketFrame

import scala.concurrent.Future

package object http {

  type Headers = Seq[(String, String)]
  type Uri = String
  type Method = String

  // The basic type that represents a HTTP service
  type HttpService = Request => Future[ResponseBuilder]

  case class Request(method: Method, uri: Uri, headers: Headers, body: () => Future[ByteBuffer])

  case class HttpResponsePrelude(code: Int, status: String, headers: Headers)

  sealed trait ResponseBuilder

  case class WSResponseBuilder(stage: LeafBuilder[WebSocketFrame]) extends ResponseBuilder

  trait RouteAction extends ResponseBuilder {
    def handle[T <: BodyWriter](responder: (HttpResponsePrelude => T)): Future[T#Finished]
  }

  object RouteAction extends ResponseFactories
}
