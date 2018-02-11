package org.http4s.blaze

import scala.concurrent.Future

package object http {
  type Headers = Seq[(String, String)]
  type Url = String
  type Method = String

  /** The basic type that represents a HTTP service
    *
    * {{{
    *   val service: HttpService = { req =>
    *     Future.success(RouteAction.Ok("Hello, world!"))
    *   }
    * }}}
    *
    * @note When the `Future` returned by the `RouteAction` resolves, server
    * implementations are free to forcibly close the request body.
    */
  type HttpService = HttpRequest => Future[RouteAction]
}
