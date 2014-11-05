package org.http4s.blaze.channel.nio1

import java.nio.channels.{SelectableChannel, SocketChannel}
import org.http4s.blaze.channel.SocketConnection
import java.net.SocketAddress

object NIO1Connection {
  def apply(connection: SelectableChannel): SocketConnection = connection match {
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

}

case class NIO1SocketConnection(connection: SocketChannel) extends SocketConnection {

  override def remote: SocketAddress = connection.getRemoteAddress

  override def local: SocketAddress = connection.getLocalAddress

  override def isOpen: Boolean = connection.isConnected

  override def close(): Unit = connection.close()
}
