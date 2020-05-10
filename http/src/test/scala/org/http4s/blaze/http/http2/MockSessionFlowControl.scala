/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2

private class MockSessionFlowControl extends SessionFlowControl {
  override def newStreamFlowWindow(streamId: Int): StreamFlowWindow = ???
  override def sessionInboundObserved(count: Int): Boolean = ???
  override def sessionOutboundAcked(count: Int): Option[Http2Exception] = ???
  override def sessionOutboundWindow: Int = ???
  override def sessionInboundConsumed(count: Int): Unit = ???
  override def sessionInboundAcked(count: Int): Unit = ???
  override def sessionInboundWindow: Int = ???
  override def sessionUnconsumedBytes: Int = ???
}
