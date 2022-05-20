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

import org.http4s.blaze.http.http2.Http2Settings.DefaultSettings
import org.http4s.blaze.http.http2.Http2Exception.{FLOW_CONTROL_ERROR, PROTOCOL_ERROR}
import org.log4s.getLogger

/** Flow control representation of a Http2 Session */
private class SessionFlowControlImpl(
    session: SessionCore,
    flowStrategy: FlowStrategy
) extends SessionFlowControl {
  private[this] val logger = getLogger

  private[this] var _sessionInboundWindow: Int =
    DefaultSettings.INITIAL_WINDOW_SIZE
  private[this] var _sessionOutboundWindow: Int =
    DefaultSettings.INITIAL_WINDOW_SIZE
  private[this] var _sessionUnconsumedInbound: Int = 0

  // exposed for testing
  protected def onSessonBytesConsumed(consumed: Int): Unit = {
    val _ = consumed
    val sessionUpdate = flowStrategy.checkSession(this)
    if (0 < sessionUpdate) {
      sessionInboundAcked(sessionUpdate)
      sendSessionWindowUpdate(sessionUpdate)
    }
  }

  // Exposed for testing
  protected def sendSessionWindowUpdate(updateSize: Int): Unit = {
    val frame = session.http2Encoder.sessionWindowUpdate(updateSize)
    session.writeController.write(frame)
    ()
  }

  /** Called when bytes have been consumed from a live stream
    *
    * @param stream
    *   stream associated with the consumed bytes
    * @param consumed
    *   number of bytes consumed
    */
  protected def onStreamBytesConsumed(stream: StreamFlowWindow, consumed: Int): Unit = {
    val _ = consumed
    val update = flowStrategy.checkStream(stream)
    if (0 < update.session) {
      sessionInboundAcked(update.session)
      session.writeController.write(session.http2Encoder.sessionWindowUpdate(update.session))
    }

    if (0 < update.stream) {
      stream.streamInboundAcked(update.stream)
      session.writeController.write(
        session.http2Encoder.streamWindowUpdate(stream.streamId, update.stream))
      ()
    }
  }

  // Exposed for testing
  protected def sendStreamWindowUpdate(stream: Int, updateSize: Int): Unit = {
    val frame = session.http2Encoder.streamWindowUpdate(stream, updateSize)
    session.writeController.write(frame)
    ()
  }

  // Concrete methods /////////////////////////////////////////////////////////////

  /** Create a new [[StreamFlowWindow]] for a stream which will update and check the bounds of the
    * session flow control state.
    *
    * @note
    *   the stream [[StreamFlowWindow]] is not thread safe.
    */
  final override def newStreamFlowWindow(streamId: Int): StreamFlowWindow = {
    require(0 < streamId)
    logger.trace(s"Created new stream: $streamId")
    new StreamFlowWindowImpl(streamId)
  }

  /** Get the number of bytes remaining in the inbound flow window */
  final override def sessionInboundWindow: Int = _sessionInboundWindow

  /** Observe inbound bytes that don't belong to an active inbound stream
    *
    * @param count
    *   bytes observed
    * @return
    *   `true` if there was sufficient session flow window remaining, `false` otherwise.
    */
  final override def sessionInboundObserved(count: Int): Boolean = {
    logger.trace(s"Observed $count inbound session bytes. $sessionWindowString")
    require(0 <= count)

    if (sessionInboundWindow < count) false
    else {
      _sessionInboundWindow -= count
      _sessionUnconsumedInbound += count
      true
    }
  }

  /** Update the session inbound window */
  final override def sessionInboundAcked(count: Int): Unit = {
    logger.trace(s"Acked $count inbound session bytes. $sessionWindowString")
    require(0 <= count)

    _sessionInboundWindow += count
  }

  /** Signal that inbound bytes have been consumed that are not tracked by a stream */
  final override def sessionInboundConsumed(count: Int): Unit = {
    logger.trace(s"Consumed $count inbound session bytes. $sessionWindowString")
    require(0 <= count)
    if (count > _sessionUnconsumedInbound) {
      val msg =
        s"Consumed more bytes ($count) than had been accounted for (${_sessionUnconsumedInbound})"
      throw new IllegalStateException(msg)
    } else if (count > 0) {
      _sessionUnconsumedInbound -= count
      onSessonBytesConsumed(count)
    }
  }

  /** Get the total number of inbound bytes that have yet to be consumed by the streams */
  final override def sessionUnconsumedBytes: Int = _sessionUnconsumedInbound

  /** Get the remaining bytes in the sessions outbound flow window */
  final override def sessionOutboundWindow: Int = _sessionOutboundWindow

  /** Update the session outbound window
    *
    * @note
    *   there is no way to withdraw outbound bytes directly from the session as there should always
    *   be an associated stream when sending flow control counted bytes outbound.
    */
  final override def sessionOutboundAcked(count: Int): Option[Http2Exception] = {
    logger.trace(s"$count outbound session bytes were ACKed. $sessionWindowString")
    // Updates MUST be greater than 0, otherwise its protocol error
    // https://tools.ietf.org/html/rfc7540#section-6.9
    if (count <= 0)
      Some(PROTOCOL_ERROR.goaway("Invalid session WINDOW_UPDATE: size <= 0."))
    else if (Int.MaxValue - sessionOutboundWindow < count)
      // A sender MUST NOT allow a flow-control window to exceed 2^31-1 octets.
      // https://tools.ietf.org/html/rfc7540#section-6.9.1
      Some(FLOW_CONTROL_ERROR.goaway("Session flow control exceeded max window."))
    else {
      _sessionOutboundWindow += count
      None
    }
  }

  // String representation of the session flow windows for debugging
  private[this] def sessionWindowString: String =
    s"Session: {inbound: $sessionInboundWindow, unconsumed: $sessionUnconsumedBytes, outbound: $sessionOutboundWindow}"

  // //////////////////////////////////////////////////////////////////

  private[this] final class StreamFlowWindowImpl(val streamId: Int) extends StreamFlowWindow {
    private[this] var _streamInboundWindow: Int =
      session.localSettings.initialWindowSize
    private[this] var _streamOutboundWindow: Int =
      session.remoteSettings.initialWindowSize
    private[this] var _streamUnconsumedInbound: Int = 0

    override def sessionFlowControl: SessionFlowControl =
      SessionFlowControlImpl.this

    override def streamUnconsumedBytes: Int = _streamUnconsumedInbound

    override def streamOutboundWindow: Int = _streamOutboundWindow

    override def streamOutboundAcked(count: Int): Option[Http2Exception] = {
      logger.trace(s"Stream($streamId) had $count outbound bytes ACKed. $streamWindowString")
      // Updates MUST be greater than 0, otherwise its protocol error
      // https://tools.ietf.org/html/rfc7540#section-6.9
      if (count <= 0)
        Some(PROTOCOL_ERROR.goaway(s"Invalid stream ($streamId) WINDOW_UPDATE: size <= 0."))
      else if (Int.MaxValue - streamOutboundWindow < count)
        // A sender MUST NOT allow a flow-control window to exceed 2^31-1 octets.
        // https://tools.ietf.org/html/rfc7540#section-6.9.1
        Some(FLOW_CONTROL_ERROR.rst(streamId, "Stream flow control exceeded max window."))
      else {
        _streamOutboundWindow += count
        None
      }
    }

    override def remoteSettingsInitialWindowChange(delta: Int): Option[Http2Exception] = {
      logger.trace(
        s"Stream($streamId) outbound window adjusted by $delta bytes. $streamWindowString")
      // A sender MUST NOT allow a flow-control window to exceed 2^31-1 octets.
      // https://tools.ietf.org/html/rfc7540#section-6.9.2
      if (Int.MaxValue - streamOutboundWindow < delta)
        Some(FLOW_CONTROL_ERROR.goaway(s"Flow control exceeded max window for stream $streamId."))
      else {
        _streamOutboundWindow += delta
        None
      }
    }

    override def outboundRequest(request: Int): Int = {
      require(0 <= request)

      val withdrawal =
        math.min(sessionOutboundWindow, math.min(request, streamOutboundWindow))
      _sessionOutboundWindow -= withdrawal
      _streamOutboundWindow -= withdrawal

      logger.trace(
        s"Stream($streamId) requested $request outbound bytes, $withdrawal were granted. $streamWindowString")
      withdrawal
    }

    override def streamInboundWindow: Int = _streamInboundWindow

    override def inboundObserved(count: Int): Boolean = {
      logger.trace(s"Stream($streamId) observed $count inbound bytes. $streamWindowString")
      require(0 <= count)
      if (count > streamInboundWindow || count > sessionInboundWindow) {
        logger.info(
          s"Stream($streamId) observed $count inbound bytes which overflowed inbound window. $streamWindowString")
        false
      } else {
        _streamUnconsumedInbound += count
        _sessionUnconsumedInbound += count

        _streamInboundWindow -= count
        _sessionInboundWindow -= count

        true
      }
    }

    override def inboundConsumed(count: Int): Unit = {
      logger.trace(s"Stream($streamId) consumed $count inbound bytes. $streamWindowString")
      require(0 <= count)
      require(count <= streamUnconsumedBytes)
      require(count <= sessionUnconsumedBytes)

      if (0 < count) {
        _streamUnconsumedInbound -= count
        _sessionUnconsumedInbound -= count

        onSessonBytesConsumed(count)
        onStreamBytesConsumed(this, count)
      }
    }

    override def streamInboundAcked(count: Int): Unit = {
      logger.trace(s"Stream($streamId) ACKed $count bytes. $streamWindowString")
      require(0 <= count)

      _streamInboundWindow += count
    }

    // String representation of the session and stream flow windows for debugging
    private[this] def streamWindowString: String =
      s"$sessionWindowString, Stream($streamId): " +
        s"{inbound: $streamInboundWindow, unconsumed: $streamUnconsumedBytes, outbound: $streamOutboundWindow}"
  }
}
