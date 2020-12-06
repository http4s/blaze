/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze

import scala.concurrent.Future

package object http {
  type Headers = collection.Seq[(String, String)]
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
