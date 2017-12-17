package org.http4s.blaze.http.http2

import org.http4s.blaze.http._

import scala.concurrent.Future

/** Manager of the active streams for a session
  *
  * The `StreamManager` can be thought of as the collection of
  * active streams and some associated helper methods for performing
  * operations relevant to the HTTP/2 protocol.
  */
private trait StreamManager {

  /** The `StreamIdManager` owned by this `StreamManager` */
  def idManager: StreamIdManager

  /** Number of active streams */
  def size: Int

  /** Whether any streams are active */
  def isEmpty: Boolean

  /** Update the flow windows of open streams due to a change of the initial flow window
    *
    * https://tools.ietf.org/html/rfc7540#section-6.9.2
    * a receiver MUST adjust the size of all stream flow-control windows that
    * it maintains by the difference between the new value and the old value.
    *
    * @param delta difference between the new initial window and the previous initial window
    */
  def initialFlowWindowChange(delta: Int): MaybeError

  /** Get the stream associated with the specified stream ID */
  def get(streamId: Int): Option[StreamState]

  /** Close the `StreamManager` and all the associated streams */
  def close(cause: Option[Throwable]): Unit

  /** Register an `InboundStreamState` with the set of active streams */
  def registerInboundStream(state: InboundStreamState): Boolean

  /** Register an `OutboundStreamState` with the set of active streams
    *
    * @return the newly allocated stream id to be associated with the new
    *         outbound stream, it it was possible to allocate one.
    */
  def registerOutboundStream(state: OutboundStreamState): Option[Int]

  /** Close the specified stream, notifying the remote peer if necessary
    *
    * @param id stream-id
    * @param cause reason for closing the stream
    * @return true if the stream existed and was closed, false otherwise
    */
  def closeStream(id: Int, cause: Option[Throwable]): Boolean

  /** Called by a [[StreamState]] to signal that it is finished. */
  def streamFinished(stream: StreamState, cause: Option[Http2Exception]): Unit

  /** Handle a valid and complete PUSH_PROMISE frame */
  def handlePushPromise(streamId: Int, promisedId: Int, headers: Headers): Http2Result

  /** Update the specified flow window with the specified bytes
    *
    * @note stream ID 0 indicates the session flow window
    */
  def flowWindowUpdate(streamId: Int, sizeIncrement: Int): MaybeError

  /** Drain the `StreamManager` */
  def goaway(lastHandledOutboundStream: Int, message: String): Future[Unit]
}
