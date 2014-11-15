package org.http4s.blaze.channel

import java.nio.channels.NetworkChannel
import java.net.SocketAddress
import java.util.Date

import org.log4s._

abstract class ServerChannelFactory[C <: NetworkChannel] {
  protected val logger = getLogger

  def bind(localAddress: SocketAddress = null): ServerChannel

  /** Decide whether to accept the incoming connection or not */
  protected def acceptConnection(connection: SocketAddress): Boolean = true

  /** perform some stateful operation when an connection is accepted */
  protected def onAccepted(address: SocketAddress): Unit = {
    logger.info(s"Connection to $address accepted at ${new Date}")
  }

  /** perform some stateful operation when an connection is rejected */
  protected def onRejected(address: SocketAddress): Unit = {
    logger.info(s"Connection to $address being denied at ${new Date}")
  }

  /** This method should be used by the backends to ensure uniform behavior with
    * respect to the above methods
    */
  final protected def doAcceptConnection(connection: SocketAddress): Boolean = {
    if (acceptConnection(connection)) {
      onAccepted(connection)
      true
    } else {
      onRejected(connection)
      false
    }
  }
}
