package org.http4s.blaze.http.endtoend.scaffolds

import java.net.InetSocketAddress
import java.nio.ByteBuffer

import org.http4s.blaze.channel.nio2.NIO2SocketServerGroup
import org.http4s.blaze.pipeline.LeafBuilder

import scala.concurrent.Future

abstract class ServerScaffold {

  protected def newLeafBuilder(): LeafBuilder[ByteBuffer]

  final def apply[T](f: InetSocketAddress => T): T = {
    val group = NIO2SocketServerGroup()

    val ch = group
      .bind(new InetSocketAddress(0), _ => Future.successful(newLeafBuilder()))
      .getOrElse(sys.error("Failed to start server."))

    try f(ch.socketAddress)
    finally ch.close()
  }
}
