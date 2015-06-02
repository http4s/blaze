package org.http4s.blaze.channel

import java.nio.channels.NetworkChannel
import java.net.SocketAddress
import java.util.Date

import org.log4s._

abstract class ServerChannelFactory[C <: NetworkChannel] {
  protected val logger = getLogger

  /** Create a [[ServerChannel]] that will serve on the specified socket */
  def bind(localAddress: SocketAddress): ServerChannel

  /** Decide whether to accept the incoming connection or not
    *
    * Should also be used for any requisite logging purposes. */
  protected def acceptConnection(address: SocketAddress): Boolean = {
    logger.info(s"Connection to $address accepted at ${new Date}.")
    true
  }
}
