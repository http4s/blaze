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

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

/** The Http2 session has a lot of interconnected pieces and the SessionCore provides a
  * 'bag-o-references' so that each component can reference each other. This helps to avoid
  * construction order conflicts.
  */
private abstract class SessionCore {
  // Fields
  def serialExecutor: ExecutionContext

  def localSettings: Http2Settings // The settings of this side

  def remoteSettings: MutableHttp2Settings // The peer's settings.

  def sessionFlowControl: SessionFlowControl

  def http2Encoder: FrameEncoder

  def writeController: WriteController

  def idManager: StreamIdManager

  def streamManager: StreamManager

  def pingManager: PingManager

  // Properties
  def state: Connection.State

  // Behaviors
  /** Shutdown the session due to unhandled exception
    *
    * This is an emergency shutdown, and the session is in an undefined state.
    * @note
    *   this method must be idempotent (even for reentrant calls) as it may be recalled by streams
    *   during the close process, etc.
    */
  def invokeShutdownWithError(ex: Option[Throwable], phase: String): Unit

  /** Signal to the session to shutdown gracefully based direction from the remote peer
    *
    * This entails draining the [[StreamManager]] and waiting for all write interests to drain.
    *
    * @see
    *   `invokeDrain` for the locally initiated analog
    */
  def invokeGoAway(lastHandledOutboundStream: Int, error: Http2SessionException): Unit

  /** Signal for the session to begin draining based on the direction of the local peer
    *
    * This entails draining the [[StreamManager]] and waiting for all write interests to drain.
    *
    * @see
    *   `invokeGoAway` for the remote initiated analog
    */
  def invokeDrain(gracePeriod: Duration): Unit
}
