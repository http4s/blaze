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
