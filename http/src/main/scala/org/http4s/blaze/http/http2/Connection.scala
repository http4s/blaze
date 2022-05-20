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

package org.http4s.blaze.http.http2

import org.http4s.blaze.http.HttpClientSession.Status
import org.http4s.blaze.pipeline.HeadStage

import scala.concurrent.Future
import scala.concurrent.duration.Duration

/** Representation of the HTTP connection or session */
private trait Connection {

  /** An estimate for the current quality of the connection
    *
    * Quality is intended to provide a metric for health of a session. Factors considered may be the
    * number of outstanding streams, available outbound streams, and flow window status and
    * behavior.
    *
    * @see
    *   [[Http2ClientSession]]
    *
    * @return
    *   a number with domain [0, 1] signifying the health or quality of the session. The scale is
    *   intended to be linear.
    */
  def quality: Double

  /** Get the status of session
    *
    * Status is intended to be used for deciding if a session is ready for dispatches or needs to be
    * cleaned up.
    *
    * @note
    *   The status is racy and is only intended to be used as advisory.
    * @see
    *   `quality` for a metric of health of the session
    */
  def status: Status

  /** The number of active streams */
  def activeStreams: Int

  /** Signal that the session should shutdown within the grace period
    *
    * Only the first invocation is guaranteed to run, and the behavior of further invocations result
    * in implementation specific behavior. The resultant `Future` will resolve once the session has
    * drained.
    */
  def drainSession(gracePeriod: Duration): Future[Unit]

  /** Ping the peer, asynchronously returning the duration of the round trip */
  def ping(): Future[Duration]

  /** Create a new outbound stream
    *
    * Resources are not necessarily allocated to this stream, therefore it is not guaranteed to
    * succeed.
    */
  // TODO: right now this only benefits the client. We need to get the push-promise support for the server side
  def newOutboundStream(): HeadStage[StreamFrame]

  /** Hook into the shutdown events of the connection */
  def onClose: Future[Unit]
}

private[blaze] object Connection {
  sealed abstract class State extends Product with Serializable {
    final def closing: Boolean = !running
    final def running: Boolean = this == Running
  }

  /** The `Running` state represents a session that is active and able to accept new streams.
    */
  case object Running extends State

  sealed abstract class Closing extends State

  /** The `Draining` state represents a session that is no longer accepting new streams and is in
    * the process of draining existing connection.
    */
  case object Draining extends Closing

  /** The `Closed` state represents a session that is completely shutdown. */
  case object Closed extends Closing
}
