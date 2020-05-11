package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.FlowStrategy.Increment

final class DefaultFlowStrategy(localSettings: Http2Settings) extends FlowStrategy {
  override def checkSession(session: SessionFlowControl): Int =
    check(
      localSettings.initialWindowSize,
      session.sessionInboundWindow,
      session.sessionUnconsumedBytes)

  override def checkStream(stream: StreamFlowWindow): Increment = {
    val sessionAck = checkSession(stream.sessionFlowControl)
    val streamAck = check(
      localSettings.initialWindowSize,
      stream.streamInboundWindow,
      stream.streamUnconsumedBytes)

    FlowStrategy.increment(sessionAck, streamAck)
  }

  private[this] def check(initialWindow: Int, currentWindow: Int, unConsumed: Int): Int = {
    val unacked = initialWindow - currentWindow
    val unackedConsumed = unacked - unConsumed
    if (unackedConsumed >= initialWindow / 2)
      // time to ack
      unackedConsumed
    else
      0
  }
}
