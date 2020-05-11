/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.channel

import java.net.InetSocketAddress

import scala.util.Try

/** Abstraction for binding a server socket and handling connections.
  *
  * @note Implementations may have resources associated with
  *       them before binding any sockets and should be closed.
  */
trait ServerChannelGroup {

  /** Create a [[ServerChannel]] that will serve the service on the specified socket */
  def bind(address: InetSocketAddress, service: SocketPipelineBuilder): Try[ServerChannel]

  /** Closes the group along with all current connections. */
  def closeGroup(): Unit
}
