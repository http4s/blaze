package org.http4s.blaze

import scala.concurrent.Future

package object http {

  type Headers = Seq[(String, String)]
  type Uri = String
  type Method = String

  // The basic type that represents a HTTP service
  type HttpService = HttpRequest => Future[RouteAction]

  /** Standard HTTP request
    *
    * @param method HTTP request method
    * @param uri request uri
    * @param headers request headers
    * @param body function which returns the next chunk of the request body. Termination is
    *             signaled by an __empty__ `ByteBuffer` as determined by `ByteBuffer.hasRemaining()`.
    */
  case class HttpRequest(method: Method, uri: Uri, majorVersion: Int, minorVersion: Int, headers: Headers, body: MessageBody)

  /** The prelude of a standard HTTP response
    *
    * @param code Response status code
    * @param status Response message. This has no meaning for the protocol, its purely for human enjoyment.
    * @param headers Response headers.
    */
  case class HttpResponsePrelude(code: Int, status: String, headers: Headers)

//  sealed trait ResponseBuilder
//
//  /** Simple HTTP response type
//    *
//    * @param action post routing response builder.
//    */
//  case class HttpResponse(action: RouteAction) extends ResponseBuilder
}
