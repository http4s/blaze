package org.http4s.blaze.channel

import java.net.InetSocketAddress
import java.util.Date

import org.log4s.getLogger

import scala.util.Try

abstract class ServerChannelGroup {
  protected val logger = getLogger

  /** Create a [[ServerChannel]] that will serve the service on the specified socket */
  def bind(address: InetSocketAddress, service: BufferPipelineBuilder): Try[ServerChannel]

  /** Closes the group along with all current connections. */
  def closeGroup(): Unit

  /** Decide whether to accept the incoming connection or not
    *
    * Should also be used for any requisite logging purposes. */
  protected def acceptConnection(address: InetSocketAddress): Boolean = {
    logger.info(s"Connection to $address accepted at ${new Date}.")
    true
  }  
}
