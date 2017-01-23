package org.http4s.blaze.http.util

import org.http4s.blaze.http.{HttpService, RouteAction}
import org.http4s.blaze.util.Execution

import scala.concurrent.duration.Duration
import scala.util.Success

/**
  * Create a new service that will race against a timer to resolve with a default value
  */
private[blaze] object ServiceTimeoutFilter {

  def apply(timeout: Duration, timeoutResult: => RouteAction)(service: HttpService): HttpService = {
    if (!timeout.isFinite()) service
    else service.andThen(Execution.withTimeout(timeout, Success(timeoutResult)))
  }

  def apply(timeout: Duration)(service: HttpService): HttpService =
    apply(timeout, newServiceTimeoutResponse(timeout))(service)

  private def newServiceTimeoutResponse(timeout: Duration): RouteAction = {
    val msg = s"Internal Timeout.\nRequest timed out after $timeout"
    RouteAction.InternalServerError(msg, List(HeaderNames.Connection -> "close"))
  }
}
