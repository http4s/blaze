package org.http4s.blaze.channel

import java.net.{InetAddress, InetSocketAddress, SocketAddress}

trait SocketConnection {

  /** Return the SocketAddress of the remote connection */
  def remote: SocketAddress

  /** Return the local SocketAddress associated with the connection */
  def local: SocketAddress

  /** Return of the connection is currently open */
  def isOpen: Boolean

  /** Close this Connection */
  def close(): Unit

  final def remoteInetAddress: Option[InetAddress] =
    remote match {
      case addr: InetSocketAddress => Option(addr.getAddress)
      case _ => None
    }

  final def localInetAddress: Option[InetAddress] =
    local match {
      case addr: InetSocketAddress => Option(addr.getAddress)
      case _ => None
    }
}
