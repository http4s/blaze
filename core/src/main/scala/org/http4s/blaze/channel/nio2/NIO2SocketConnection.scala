/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.channel.nio2

import org.http4s.blaze.channel.SocketConnection
import java.nio.channels.AsynchronousSocketChannel
import java.net.SocketAddress

private[nio2] final class NIO2SocketConnection(connection: AsynchronousSocketChannel)
    extends SocketConnection {
  override def remote: SocketAddress = connection.getRemoteAddress

  override def local: SocketAddress = connection.getLocalAddress

  override def isOpen: Boolean = connection.isOpen

  override def close(): Unit = connection.close()
}
