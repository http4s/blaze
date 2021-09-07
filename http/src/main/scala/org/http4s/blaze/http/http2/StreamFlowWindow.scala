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

/** Representation of the flow control state of a stream belonging to a session
  *
  * The `StreamFlowWindow` provides the tools for tracking the flow window for both the individual
  * stream and the session that it belongs to.
  */
abstract class StreamFlowWindow {

  /** The flow control manager of the session this stream belongs to */
  def sessionFlowControl: SessionFlowControl

  /** Id of the associated stream */
  def streamId: Int

  /** Get the number of stream inbound bytes that haven't been consumed */
  def streamUnconsumedBytes: Int

  /** Get the remaining bytes in the stream outbound window */
  def streamOutboundWindow: Int

  /** Get the remaining outbound window, considering both the session and stream windows */
  final def outboundWindow: Int =
    math.min(streamOutboundWindow, sessionFlowControl.sessionOutboundWindow)

  /** Determine whether we have available flow window remaining, considering both the stream and the
    * session flow windows
    */
  final def outboundWindowAvailable: Boolean =
    streamOutboundWindow > 0 && sessionFlowControl.sessionOutboundWindow > 0

  /** Adjust the stream flow window to account for a change in INITIAL_WINDOW_SIZE
    *
    * If an error is returned, the internal state _must not_ be modified.
    *
    * @param delta
    *   change in intial window size. Maybe be positive or negative, but must not cause the window
    *   to overflow Int.MaxValue.
    */
  def remoteSettingsInitialWindowChange(delta: Int): Option[Http2Exception]

  /** Signal that a stream window update was received for `count` bytes */
  def streamOutboundAcked(count: Int): Option[Http2Exception]

  /** Request to withdraw bytes from the outbound window of the stream and the session.
    *
    * @param request
    *   maximum bytes to withdraw
    * @return
    *   actual bytes withdrawn from the window
    */
  def outboundRequest(request: Int): Int

  /** Get the remaining bytes in the streams inbound window */
  def streamInboundWindow: Int

  /** Attempts to withdraw `count` bytes from the inbound window of both the stream and the session.
    *
    * If there are sufficient bytes in the stream and session flow windows, they are subtracted,
    * otherwise the window is unmodified.
    *
    * @return
    *   `true` if withdraw was successful, `false` otherwise.
    */
  def inboundObserved(count: Int): Boolean

  /** Signal that `count` bytes have been consumed by the stream
    *
    * @note
    *   The consumed bytes are also counted for the session flow window.
    */
  def inboundConsumed(count: Int): Unit

  /** Signal that a stream window update was sent for `count` bytes */
  def streamInboundAcked(count: Int): Unit
}
