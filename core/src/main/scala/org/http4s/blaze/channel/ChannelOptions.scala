package org.http4s.blaze.channel

import java.net.SocketOption
import java.nio.channels.NetworkChannel

/** key-value pair composing a socket option */
case class OptionValue[T](key: SocketOption[T], value: T)

/** Collection of socket options */
case class ChannelOptions(options: Vector[OptionValue[_]]) {
  def applyToChannel(channel: NetworkChannel): Unit =
    options.foreach { case OptionValue(k, v) => channel.setOption(k, v) }
}

object ChannelOptions {
  def apply(options: OptionValue[_]*): ChannelOptions =
    ChannelOptions(options.toVector)

  val DefaultOptions: ChannelOptions = apply(
    OptionValue[java.lang.Boolean](java.net.StandardSocketOptions.TCP_NODELAY, true)
  )
}
