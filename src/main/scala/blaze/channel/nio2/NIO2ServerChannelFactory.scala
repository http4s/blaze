package blaze.channel.nio2

import java.net.SocketAddress

import java.nio.channels.{AsynchronousServerSocketChannel,
                          AsynchronousSocketChannel,
                          AsynchronousChannelGroup}

import scala.annotation.tailrec
import blaze.pipeline.PipelineBuilder
import java.nio.ByteBuffer
import com.typesafe.scalalogging.slf4j.Logging
import java.util.Date
import blaze.channel._
import blaze.pipeline.Command.Connected


/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
class NIO2ServerChannelFactory(pipeFactory: PipeFactory, group: AsynchronousChannelGroup = null)
        extends ServerChannelFactory[AsynchronousServerSocketChannel] with Logging {

  // Intended to be overridden in order to allow the reject of connections
  protected def acceptConnection(channel: AsynchronousSocketChannel): Boolean = true

  def bind(localAddress: SocketAddress = null): ServerChannel = {
    if (pipeFactory == null) sys.error("Pipeline factory required")
    new NIO2ServerChannel(AsynchronousServerSocketChannel.open(group).bind(localAddress))
  }
  
  private def root(ch: AsynchronousSocketChannel): PipelineBuilder[ByteBuffer, ByteBuffer] = {
    val root = new ByteBufferHead(ch)
    PipelineBuilder(root)
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

          if (!acceptConnection(ch)) {
            logger.trace(s"Connection to ${ch.getRemoteAddress} being denied at ${new Date}")
            ch.close()
          }
          else {
            logger.trace(s"Connection to ${ch.getRemoteAddress} accepted at ${new Date}")
            pipeFactory(root(ch)).inboundCommand(Connected)
          }

        } catch {
          case e: InterruptedException => continue = false

        }
        if (continue) run()
      }
      else sys.error("Channel closed")
    }
  }
}
