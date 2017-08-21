package org.http4s.blaze

import scala.concurrent.Future

package object http {
  type Headers = Seq[(String, String)]
  type Url = String
  type Method = String

  // The basic type that represents a HTTP service
  type HttpService = HttpRequest => Future[RouteAction]
}
