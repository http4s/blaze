package org.http4s.blaze.channel.nio1

import java.net.InetSocketAddress
import java.nio.channels.ServerSocketChannel

import org.http4s.blaze.channel.{BufferPipelineBuilder, ServerChannel}

import scala.util.control.NonFatal

/** Implementation of a [[ServerChannel]] for nio1 */
private class ServerChannelImpl(
    val serverSocketChannel: ServerSocketChannel,
    val service: BufferPipelineBuilder)
    extends ServerChannel {

  override protected def closeChannel(): Unit = {
    logger.info(s"Closing NIO1 channel ${serverSocketChannel.getLocalAddress}")
    try serverSocketChannel.close()
    catch {
      case NonFatal(t) => logger.debug(t)("Failure during channel close")
    }
  }

  def socketAddress: InetSocketAddress =
    serverSocketChannel.getLocalAddress.asInstanceOf[InetSocketAddress]
}
