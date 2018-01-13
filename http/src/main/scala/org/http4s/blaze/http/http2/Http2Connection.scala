package org.http4s.blaze.http.http2

import org.http4s.blaze.http.{Http2ClientSession, HttpClientSession}
import org.http4s.blaze.http.HttpClientSession.Status
import org.http4s.blaze.http.http2.Http2Connection.ConnectionState
import org.http4s.blaze.pipeline.HeadStage

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.Duration

trait Http2Connection {

  /** Get the current state of the session */
  def state: ConnectionState

  /** An estimate for the current quality of the connection
    *
    * Quality is intended to provide a metric for health of a session.
    * Factors considered may be the number of outstanding streams, available
    * outbound streams, and flow window status and behavior.
    *
    * @see [[Http2ClientSession]]
    *
    * @return a number with domain [0, 1] signifying the health or quality of
    *         the session. The scale is intended to be linear.
    */
  def quality: Double

  /** Get the status of session
    *
    * Status is intended to be used for deciding if a session is ready for
    * dispatches or needs to be cleaned up.
    *
    * @note The status is racy and is only intended to be used as advisory.
    * @see `quality` for a metric of health of the session
    */
  def status: Status

  /** Signal that the session should shutdown within the grace period
    *
    * Only the first invocation is guaranteed to run, and the behavior of further
    * invocations result in implementation specific behavior. The resultant
    * `Future` will resolve once the session has drained.
    */
  def drainSession(gracePeriod: Duration): Future[Unit]

  /** Ping the peer, asynchronously returning the duration of the round trip
    *
    * @note the resulting duration includes the processing time at both peers.
    */
  def ping: Future[Duration]
}

trait Http2ClientConnection extends Http2Connection with Http2ClientSession {

  /** Create a new outbound stream
    *
    * Resources are not necessarily allocated to this stream, therefore it is
    * not guaranteed to succeed.
    */
  def newOutboundStream(): HeadStage[StreamMessage]
}

object Http2Connection {
  sealed abstract class ConnectionState extends Product with Serializable {
    final def closing: Boolean = !running
    final def running: Boolean = this == Running
  }

  /** The `Running` state represents a session that is active and able to accept
    * new streams.
    */
  case object Running extends ConnectionState

  sealed abstract class Closing extends ConnectionState

  /** The `Draining` state represents a session that is no longer accepting new
    * streams and is in the process of draining existing connection.
    */
  case object Draining extends Closing

  /** The `Closed` state represents a session that is completely shutdown. */
  case object Closed extends Closing
}
