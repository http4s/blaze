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

import org.http4s.blaze.http.http2._
import org.http4s.blaze.util.Execution

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

private[http2] class MockTools(isClient: Boolean) extends SessionCore {
  def flowStrategy: FlowStrategy = new DefaultFlowStrategy(localSettings)

  override lazy val localSettings: MutableHttp2Settings = MutableHttp2Settings.default()

  override lazy val remoteSettings: MutableHttp2Settings = MutableHttp2Settings.default()

  lazy val headerEncoder: HeaderEncoder = new HeaderEncoder(remoteSettings.headerTableSize)

  lazy val frameEncoder: FrameEncoder = new FrameEncoder(remoteSettings, headerEncoder)

  lazy val idManager: StreamIdManager = StreamIdManager(isClient)

  override lazy val serialExecutor: ExecutionContext = Execution.trampoline

  override lazy val sessionFlowControl: SessionFlowControl =
    new SessionFlowControlImpl(this, flowStrategy)

  override lazy val http2Encoder: FrameEncoder =
    new FrameEncoder(remoteSettings, headerEncoder)

  override val writeController: MockWriteController = new MockWriteController

  override lazy val pingManager: PingManager = new PingManager(this)

  override lazy val streamManager: StreamManager = ???

  // Behaviors
  override def state: Connection.State = Connection.Running

  var drainGracePeriod: Option[Duration] = None

  override def invokeDrain(gracePeriod: Duration): Unit =
    drainGracePeriod = Some(gracePeriod)

  override def invokeGoAway(lastHandledOutboundStream: Int, reason: Http2SessionException): Unit =
    ???

  override def invokeShutdownWithError(ex: Option[Throwable], phase: String): Unit = ???
}
