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

package org.http4s.blaze.http.util

import org.http4s.blaze.http.{HeaderNames, HttpService, RouteAction}
import org.http4s.blaze.util.Execution

import scala.concurrent.duration.Duration
import scala.util.Success

/** Create a new service that will race against a timer to resolve with a default value
  */
private[blaze] object ServiceTimeoutFilter {
  def apply(timeout: Duration, timeoutResult: => RouteAction)(service: HttpService): HttpService =
    if (!timeout.isFinite) service
    else service.andThen(Execution.withTimeout(timeout, Success(timeoutResult)))

  def apply(timeout: Duration)(service: HttpService): HttpService =
    apply(timeout, newServiceTimeoutResponse(timeout))(service)

  private def newServiceTimeoutResponse(timeout: Duration): RouteAction = {
    val msg = s"Internal Timeout.\nRequest timed out after $timeout"
    RouteAction.InternalServerError(msg, List(HeaderNames.Connection -> "close"))
  }
}
