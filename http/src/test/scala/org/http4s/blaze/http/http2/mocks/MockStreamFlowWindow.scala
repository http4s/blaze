package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.http2.{MaybeError, SessionFlowControl, StreamFlowWindow}

private[http2] class MockStreamFlowWindow extends StreamFlowWindow {
  override def sessionFlowControl: SessionFlowControl = ???
  override def streamUnconsumedBytes: Int = ???
  override def outboundRequest(request: Int): Int = ???
  override def streamId: Int = ???
  override def inboundObserved(count: Int): Boolean = ???
  override def peerSettingsInitialWindowChange(delta: Int): MaybeError = ???
  override def streamInboundWindow: Int = ???
  override def streamOutboundWindow: Int = ???
  override def inboundConsumed(count: Int): Unit = ???
  override def streamInboundAcked(count: Int): Unit = ???
  override def streamOutboundAcked(count: Int): MaybeError = ???
}