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
