package org.http4s.blaze.http.http2

/** Flow control representation of a Http2 Session */
abstract class SessionFlowControl {

  /** Create a new [[StreamFlowWindow]] for a stream which will update and check the
    * bounds of the session flow control state.
    *
    * @note the stream [[StreamFlowWindow]] is not thread safe.
    */
  def newStreamFlowWindow(streamId: Int): StreamFlowWindow

  /** Get the number of bytes remaining in the inbound flow window */
  def sessionInboundWindow: Int

  /** Observe inbound bytes that don't belong to an active inbound stream
    *
    * @param count bytes observed
    * @return `true` if there was sufficient session flow window remaining, `false` otherwise.
    */
  def sessionInboundObserved(count: Int): Boolean

  /** Update the session inbound window */
  def sessionInboundAcked(count: Int): Unit

  /** Signal that inbound bytes have been consumed that are not tracked by a stream */
  def sessionInboundConsumed(count: Int): Unit

  /** Get the total number of inbound bytes that have yet to be consumed by the streams */
  def sessionUnconsumedBytes: Int

  /** Get the remaining bytes in the sessions outbound flow window */
  def sessionOutboundWindow: Int

  /** Update the session outbound window
    *
    * @note there is no way to withdraw outbound bytes directly from
    *       the session as there should always be an associated stream
    *       when sending flow control counted bytes outbound.
    */
  def sessionOutboundAcked(count: Int): Option[Http2Exception]
}
