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

package org.http4s.blaze.channel.nio1

import java.nio.channels.{SelectableChannel, SocketChannel}
import org.http4s.blaze.channel.SocketConnection
import java.net.SocketAddress

object NIO1Connection {
  def apply(connection: SelectableChannel): SocketConnection =
    connection match {
      case ch: SocketChannel => NIO1SocketConnection(ch)
      case _ =>
        // We don't know what type this is, so implement what we can
        new SocketConnection {
          override def remote: SocketAddress = local

          override def local: SocketAddress = new SocketAddress {}

          override def close(): Unit = connection.close()

          override def isOpen: Boolean = connection.isOpen
        }
    }

  private[blaze] def apply(channel: NIO1ClientChannel): SocketConnection =
    new SocketConnection {
      override def remote: SocketAddress = channel.getRemoteAddress
      override def local: SocketAddress = channel.getLocalAddress
      override def isOpen: Boolean = channel.isOpen
      override def close(): Unit = channel.close()
    }
}

private case class NIO1SocketConnection(connection: SocketChannel) extends SocketConnection {
  override def remote: SocketAddress = connection.getRemoteAddress

  override def local: SocketAddress = connection.getLocalAddress

  override def isOpen: Boolean = connection.isConnected

  override def close(): Unit = connection.close()
}
