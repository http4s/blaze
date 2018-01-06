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

  /** Number of active streams */
  def size: Int

  /** Whether any streams are active */
  def isEmpty: Boolean

  /** Update the flow windows of open streams due to a change of the initial flow window
    *
    * A receiver MUST adjust the size of all stream flow-control windows that
    * it maintains by the difference between the new value and the old value.
    * https://tools.ietf.org/html/rfc7540#section-6.9.2
    *
    * @param delta difference between the new initial window and the previous initial window
    */
  def initialFlowWindowChange(delta: Int): MaybeError

  /** Get the stream associated with the specified stream ID */
  def get(streamId: Int): Option[StreamState]

  /** Potentially create a new [[InboundStreamState]] for the provided stream id
    *
    * Validates the state of the session accordingly.
    */
  def newInboundStream(streamId: Int): Either[Http2Exception, InboundStreamState]

  /** Creates a new OutboundStreamState which hasn't been allocated a stream id
    *
    * Errors are returned lazily since resources aren't acquired until the write of
    * the streams prelude.
    */
  def newOutboundStream(): OutboundStreamState

  /** Cause the associated stream to be reset, if it exists
    *
    * @param cause the reason the stream was reset
    */
  def rstStream(cause: Http2StreamException): MaybeError

  /** Called by a `StreamState` to remove itself from the StreamManager
    *
    * @param streamState the stream being closed
    * @return true if the stream existed and was closed, false otherwise
    */
  def streamClosed(streamState: StreamState): Boolean

  /** Handle a valid and complete PUSH_PROMISE frame */
  def handlePushPromise(streamId: Int, promisedId: Int, headers: Headers): Http2Result

  /** Update the specified flow window with the specified bytes
    *
    * @note stream ID 0 indicates the session flow window
    */
  def flowWindowUpdate(streamId: Int, sizeIncrement: Int): MaybeError

  /** Close the `StreamManager` and all the associated streams immediately
    *
    * Close all the streams of the session now, most commonly due to an error
    * in the session. For a controlled shutdown, use `goAway`.
    */
  def forceClose(cause: Option[Throwable]): Unit

  /** Drain the `StreamManager` gracefully
    *
    * All outbound streams with ID's above the specified last handled ID will
    * be reset with a REFUSED_STREAM stream error to signal that they were
    * rejected by the remote peer.
    *
    * @return a `Future` that will resolve once all streams have been drained
    */
  def goAway(lastHandledOutboundStream: Int, message: String): Future[Unit]
}
