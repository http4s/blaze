package org.http4s.blaze.channel

import java.nio.channels.NetworkChannel
import java.net.SocketAddress

/**
 * @author Bryce Anderson
 *         Created on 1/23/14
 */
trait ServerChannelFactory[C <: NetworkChannel] {
  def bind(localAddress: SocketAddress = null): ServerChannel
}
