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

import java.net.{SocketAddress, SocketOption}
import java.nio.channels.NetworkChannel
import java.util.concurrent.atomic.AtomicBoolean

import munit.FunSuite

class ChannelOptionsSuite extends FunSuite {
  test("A ChannelOptions should be set on a NetworkChannel") {
    val options = ChannelOptions(
      OptionValue[java.lang.Boolean](java.net.StandardSocketOptions.TCP_NODELAY, true),
      OptionValue[java.lang.Boolean](java.net.StandardSocketOptions.SO_KEEPALIVE, false)
    )

    val ch = new NetworkChannel {
      private[this] val tcpNoDelayOption = new AtomicBoolean(false)
      private[this] val soKeepAliveOption = new AtomicBoolean(true)
      def bind(socketAddress: SocketAddress): NetworkChannel = ???
      def getLocalAddress: SocketAddress = ???
      def setOption[T](socketOption: SocketOption[T], t: T): NetworkChannel =
        socketOption.name() match {
          case "TCP_NODELAY" =>
            tcpNoDelayOption.set(true)
            this

          case "SO_KEEPALIVE" =>
            soKeepAliveOption.set(false)
            this

          case _ =>
            this
        }
      def getOption[T](socketOption: SocketOption[T]): T = socketOption.name() match {
        case "TCP_NODELAY" => tcpNoDelayOption.get().asInstanceOf[T]
        case "SO_KEEPALIVE" => soKeepAliveOption.get().asInstanceOf[T]
        case _ => throw new IllegalStateException("Not expected option")
      }

      def supportedOptions() = ???
      def isOpen: Boolean = ???
      def close(): Unit = ???
    }

    options.applyToChannel(ch)

    assertEquals(
      ch.getOption[java.lang.Boolean](java.net.StandardSocketOptions.TCP_NODELAY),
      java.lang.Boolean.TRUE)
    assertEquals(
      ch.getOption[java.lang.Boolean](java.net.StandardSocketOptions.SO_KEEPALIVE),
      java.lang.Boolean.FALSE)
  }
}
