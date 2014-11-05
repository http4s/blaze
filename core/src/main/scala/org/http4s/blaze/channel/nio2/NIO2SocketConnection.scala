package org.http4s.blaze.channel.nio2

import org.http4s.blaze.channel.SocketConnection
import java.nio.channels.AsynchronousSocketChannel
import java.net.SocketAddress

case class NIO2SocketConnection(connection: AsynchronousSocketChannel) extends SocketConnection {

  override def remote: SocketAddress = connection.getRemoteAddress

  override def local: SocketAddress = connection.getLocalAddress

  override def isOpen: Boolean = connection.isOpen

  override def close(): Unit = connection.close()
}
