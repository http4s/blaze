package org.http4s.blaze.channel

import java.net.InetSocketAddress
import java.util.Date

import org.log4s.getLogger

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
