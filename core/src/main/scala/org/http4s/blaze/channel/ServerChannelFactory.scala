package org.http4s.blaze.channel

import java.nio.channels.NetworkChannel
import java.net.SocketAddress

trait ServerChannelFactory[C <: NetworkChannel] {
  def bind(localAddress: SocketAddress = null): ServerChannel
}
