package org.http4s.blaze.channel.nio2

import java.net.SocketAddress
import java.nio.channels.{AsynchronousServerSocketChannel, AsynchronousChannelGroup}

import scala.annotation.tailrec

import org.http4s.blaze.channel._
import org.http4s.blaze.pipeline.Command.Connected


class NIO2ServerChannelFactory(pipeFactory: BufferPipelineBuilder, group: AsynchronousChannelGroup = null)
        extends ServerChannelFactory[AsynchronousServerSocketChannel] {

  def bind(localAddress: SocketAddress = null): ServerChannel = {
    if (pipeFactory == null) sys.error("Pipeline factory required")
    new NIO2ServerChannel(AsynchronousServerSocketChannel.open(group).bind(localAddress))
  }

  private class NIO2ServerChannel(protected val channel: AsynchronousServerSocketChannel)
                extends ServerChannel {

    type C = AsynchronousServerSocketChannel

    @tailrec
    final def run():Unit = {
      if (channel.isOpen) {
        var continue = true
        try {
          val ch = channel.accept().get() // Will synchronize here
          val addr = ch.getRemoteAddress

          if (!doAcceptConnection(addr)) ch.close()
          else pipeFactory(NIO2SocketConnection(ch))
                .base(new ByteBufferHead(ch))
                .sendInboundCommand(Connected)

        } catch {
          case e: InterruptedException => continue = false

        }
        if (continue) run()
      }
      else sys.error("Channel closed")
    }
  }
}
