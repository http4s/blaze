package org.http4s.blaze.channel.nio2

import java.net.SocketAddress
import java.nio.channels.{AsynchronousCloseException, AsynchronousServerSocketChannel, AsynchronousChannelGroup}

import scala.annotation.tailrec

import org.http4s.blaze.channel._
import org.http4s.blaze.pipeline.Command.Connected

import scala.concurrent.ExecutionException


object NIO2SocketServerChannelFactory {
  def apply(pipeFactory: BufferPipelineBuilder, group: Option[AsynchronousChannelGroup] = None): NIO2SocketServerChannelFactory =
    new NIO2SocketServerChannelFactory(pipeFactory, group.orNull)
}

class NIO2SocketServerChannelFactory private(pipeFactory: BufferPipelineBuilder,
                                                   group: AsynchronousChannelGroup = null)
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
        var continue = false
        try {
          val ch = channel.accept().get() // Will synchronize here
          val addr = ch.getRemoteAddress

          if (!doAcceptConnection(addr)) ch.close()
          else pipeFactory(NIO2SocketConnection(ch))
                .base(new ByteBufferHead(ch))
                .sendInboundCommand(Connected)

          continue = true

        } catch {
          case e: ExecutionException if e.getCause().isInstanceOf[AsynchronousCloseException] => /* NOOP */
          case _: InterruptedException        => /* NOOP */
        }
        if (continue) run()
      }
    }
  }
}
