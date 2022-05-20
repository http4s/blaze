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

package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.http2.{Http2Exception, SessionFlowControl, StreamFlowWindow}

private[http2] class MockStreamFlowWindow extends StreamFlowWindow {
  override def sessionFlowControl: SessionFlowControl = ???
  override def streamUnconsumedBytes: Int = ???
  override def outboundRequest(request: Int): Int = ???
  override def streamId: Int = ???
  override def inboundObserved(count: Int): Boolean = ???
  override def remoteSettingsInitialWindowChange(delta: Int): Option[Http2Exception] = ???
  override def streamInboundWindow: Int = ???
  override def streamOutboundWindow: Int = ???
  override def inboundConsumed(count: Int): Unit = ???
  override def streamInboundAcked(count: Int): Unit = ???
  override def streamOutboundAcked(count: Int): Option[Http2Exception] = ???
}
