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

package org.http4s.blaze.channel

import java.net.InetSocketAddress

import scala.util.Try

/** Abstraction for binding a server socket and handling connections.
  *
  * @note
  *   Implementations may have resources associated with them before binding any sockets and should
  *   be closed.
  */
trait ServerChannelGroup {

  /** Create a [[ServerChannel]] that will serve the service on the specified socket */
  def bind(address: InetSocketAddress, service: SocketPipelineBuilder): Try[ServerChannel]

  /** Closes the group along with all current connections. */
  def closeGroup(): Unit
}
