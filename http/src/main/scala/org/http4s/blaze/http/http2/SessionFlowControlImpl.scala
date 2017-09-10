package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.Http2Settings.DefaultSettings
import org.http4s.blaze.http.http2.Http2Exception.{FLOW_CONTROL_ERROR, PROTOCOL_ERROR}
import org.log4s.getLogger


/** Flow control representation of a Http2 Session
  *
  * @param localSettings HTTP2 settings to be used for creating new stream inbound windows
  * @param peerSettings HTTP2 settings to be used for creating new stream outbound windows
  */
private abstract class SessionFlowControlImpl(
  localSettings: Http2Settings,
  peerSettings: Http2Settings
) extends SessionFlowControl {

  private[this] val logger = getLogger

  private[this] var _sessionInboundWindow: Int = DefaultSettings.INITIAL_WINDOW_SIZE
  private[this] var _sessionOutboundWindow: Int = DefaultSettings.INITIAL_WINDOW_SIZE
  private[this] var _sessionUnconsumedInbound: Int = 0

  /** Called when bytes have been consumed that are not associated with a live stream id.
    *
    * @param consumed the number of bytes consumed.
    */
  protected def onSessonBytesConsumed(consumed: Int): Unit

  /** Called when bytes have been consumed from a live stream
    *
    * @param stream stream associated with the consumed bytes
    * @param consumed
    */
  protected def onStreamBytesConsumed(stream: StreamFlowWindow, consumed: Int): Unit

  // Concrete methods /////////////////////////////////////////////////////////////

  /** Create a new [[StreamFlowWindow]] for a stream which will update and check the
    * bounds of the session flow control state.
    *
    * @note the stream [[StreamFlowWindow]] is not thread safe.
    */
  final override def newStreamFlowWindow(streamId: Int): StreamFlowWindow = {
    require(streamId > 0)
    logger.trace(s"Created new stream: $streamId")
    new StreamFlowWindowImpl(streamId)
  }

  /** Get the number of bytes remaining in the inbound flow window */
  final override def sessionInboundWindow: Int = _sessionInboundWindow

  /** Observe inbound bytes that don't belong to an active inbound stream
    *
    * @param count bytes observed
    * @return `true` if there was sufficient session flow window remaining, `false` otherwise.
    */
  final override def sessionInboundObserved(count: Int): Boolean = {
    require(count >= 0)
    logger.trace(s"Observed $count inbound session bytes")
    if (sessionInboundWindow < count) false
    else {
      _sessionInboundWindow -= count
      _sessionUnconsumedInbound += count
      true
    }
  }

  /** Update the session inbound window */
  final override def sessionInboundAcked(count: Int): Unit = {
    require(count >= 0)
    logger.trace(s"Acked $count inbound session bytes")
    _sessionInboundWindow += count
  }

  /** Signal that inbound bytes have been consumed that are not tracked by a stream */
  final override def sessionInboundConsumed(count: Int): Unit = {
    require(count >= 0)
    logger.trace(s"Consumed $count inbound session bytes")
    if (count > _sessionUnconsumedInbound) {
      val msg = s"Consumed more bytes ($count) than had been accounted for (${_sessionUnconsumedInbound})"
      throw new IllegalStateException(msg)
    } else if (count > 0) {
      _sessionUnconsumedInbound -= count
      onSessonBytesConsumed(count)
    }
  }

  /** Get the total number of inbound bytes that have yet to be consumed by the streams */
  final override def sessionUnconsumedBytes: Int = _sessionUnconsumedInbound

  /** Get the remaining bytes in the sessions outbound flow window */
  final override def sessionOutboundWindow: Int =  _sessionOutboundWindow

  /** Update the session outbound window
    *
    * @note there is no way to withdraw outbound bytes directly from
    *       the session as there should always be an associated stream
    *       when sending flow control counted bytes outbound.
    */
  final override def sessionOutboundAcked(count: Int): MaybeError = {
    logger.trace(s"$count outbound session bytes were ACKed")
    // Updates MUST be greater than 0, otherwise its protocol error
    // https://tools.ietf.org/html/rfc7540#section-6.9
    if (count <= 0) {
      Error(PROTOCOL_ERROR.goaway("Invalid session WINDOW_UPDATE: size <= 0."))
    } else if (Int.MaxValue - sessionOutboundWindow < count) {
      // A sender MUST NOT allow a flow-control window to exceed 2^31-1 octets.
      // https://tools.ietf.org/html/rfc7540#section-6.9.1
      Error(FLOW_CONTROL_ERROR.goaway("Session flow control exceeded max window."))
    } else {
      _sessionOutboundWindow += count
      Continue
    }
  }

  ////////////////////////////////////////////////////////////////////

  private[this] final class StreamFlowWindowImpl(val streamId: Int)
    extends StreamFlowWindow {

    private[this] var _streamInboundWindow: Int = localSettings.initialWindowSize
    private[this] var _streamOutboundWindow: Int = peerSettings.initialWindowSize
    private[this] var _streamUnconsumedInbound: Int = 0

    override def sessionFlowControl: SessionFlowControl = SessionFlowControlImpl.this

    override def streamUnconsumedBytes: Int = _streamUnconsumedInbound

    override def streamOutboundWindow: Int = _streamOutboundWindow

    override def peerSettingsInitialWindowChange(delta: Int): MaybeError =
      adjustOutbound(delta)

    override def streamOutboundAcked(count: Int): MaybeError = {
      logger.trace(s"Stream($streamId) had $count outbound bytes ACKed")
      // Updates MUST be greater than 0, otherwise its protocol error
      // https://tools.ietf.org/html/rfc7540#section-6.9
      if (count <= 0) {
        Error(PROTOCOL_ERROR.goaway(
          s"Invalid stream ($streamId) WINDOW_UPDATE: size <= 0."))
      } else if (Int.MaxValue - sessionOutboundWindow < count) {
        // A sender MUST NOT allow a flow-control window to exceed 2^31-1 octets.
        // https://tools.ietf.org/html/rfc7540#section-6.9.1
        Error(FLOW_CONTROL_ERROR.rst(streamId, "Session flow control exceeded max window."))
      } else {
        adjustOutbound(count)
      }
    }

    private[this] def adjustOutbound(delta: Int): MaybeError = {
      logger.trace(s"Stream($streamId) outbound window adjusted by $delta bytes")
      // A sender MUST NOT allow a flow-control window to exceed 2^31-1 octets.
      // https://tools.ietf.org/html/rfc7540#section-6.9.1
      if (Int.MaxValue - sessionOutboundWindow < delta) {
        Error(FLOW_CONTROL_ERROR.goaway(s"Flow control exceeded max window for stream $streamId."))
      } else {
        _streamOutboundWindow += delta
        Continue
      }
    }

    override def outboundRequest(request: Int): Int = {
      require(request >= 0)

      val withdrawal = math.min(sessionOutboundWindow, math.min(request, streamOutboundWindow))
      _sessionOutboundWindow -= withdrawal
      _streamOutboundWindow -= withdrawal

      logger.trace(s"Stream($streamId) requested $request outbound bytes, $withdrawal were granted.")
      withdrawal
    }

    override def streamInboundWindow: Int =  _streamInboundWindow

    override def inboundObserved(count: Int): Boolean = {
      require(count >= 0)
      if (count > streamInboundWindow || count > sessionInboundWindow) {
        logger.trace(s"Stream($streamId) observed $count inbound bytes which overflowed inbound window, " +
          s"(stream window: $streamInboundWindow, session window: $sessionInboundWindow)")
        false
      }
      else {
        logger.trace(s"Stream($streamId) observed $count inbound bytes")
        _streamUnconsumedInbound += count
        _sessionUnconsumedInbound += count

        _streamInboundWindow -= count
        _sessionInboundWindow -= count

        true
      }
    }

    override def inboundConsumed(count: Int): Unit = {
      require(count >= 0 && count <= streamUnconsumedBytes && count <= sessionUnconsumedBytes)

      logger.trace(s"Stream($streamId) consumed $count inbound bytes")

      if (count > 0) {
        _streamUnconsumedInbound -= count
        _sessionUnconsumedInbound -= count

        onSessonBytesConsumed(count)
        onStreamBytesConsumed(this, count)
      }
    }

    override def streamInboundAcked(count: Int): Unit = {
      require(count >= 0)
      logger.trace(s"Stream($streamId) ACKed $count bytes")
      _streamInboundWindow += count
    }
  }
}
