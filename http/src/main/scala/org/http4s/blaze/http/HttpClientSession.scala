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

package org.http4s.blaze.http

import org.http4s.blaze.http.HttpClientSession.{Closed, Ready, ReleaseableResponse, Status}

import scala.concurrent.Future
import scala.concurrent.duration.Duration

/** Representation of a concrete HTTP session
  *
  * The [[HttpClientSession]] represents a true HTTP client session, either HTTP/1.x or HTTP/2.
  */
sealed trait HttpClientSession {

  /** Dispatch a [[HttpRequest]]
    *
    * The resultant `ReleaseableResponse` contains a
    */
  def dispatch(request: HttpRequest): Future[ReleaseableResponse]

  /** Get the `Status` of session */
  def status: Status

  /** Return whether the client session is in the `Ready` state */
  final def isReady: Boolean = status == Ready

  /** Return whether the client session is in the `Closed` state */
  final def isClosed: Boolean = status == Closed

  /** Close the session within the specified duration.
    *
    * The provided duration is only an advisory upper bound and the implementation may choose to
    * close the session at any time before the expiration of the `within` parameter. The returned
    * `Future[Unit]` will resolve when all resources have been freed from the client.
    *
    * @note
    *   This will generally entail closing the socket connection.
    */
  def close(within: Duration): Future[Unit]

  /** Close the session immediately, regardless of any pending dispatches */
  final def closeNow(): Future[Unit] = close(Duration.Zero)
}

trait Http1ClientSession extends HttpClientSession

trait Http2ClientSession extends HttpClientSession {

  /** An estimate for the current quality of the session
    *
    * @return
    *   a number with domain [0, 1] signifying the health or quality of the session. The scale is
    *   intended to be linear.
    */
  def quality: Double

  /** Ping the peer, asynchronously returning the duration of the round trip */
  def ping(): Future[Duration]
}

object HttpClientSession {

  /** ClientResponse that can be released */
  trait ReleaseableResponse extends ClientResponse {

    /** Releases the resources associated with this dispatch
      *
      * This may entail closing the connection or returning it to a connection pool, depending on
      * the client implementation and the state of the session responsible for dispatching the
      * associated request.
      *
      * @note
      *   `release()` must be idempotent and never throw an exception, otherwise the session will be
      *   corrupted.
      */
    def release(): Unit
  }

  sealed trait Status

  /** Able to dispatch now */
  case object Ready extends Status

  /** The session is busy and cannot dispatch a message at this time, but may be able to in the
    * future
    */
  case object Busy extends Status

  /** The session will no longer be able to dispatch requests */
  case object Closed extends Status
}
