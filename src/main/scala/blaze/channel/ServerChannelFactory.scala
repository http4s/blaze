package blaze
package channel

import java.net.SocketAddress

import java.nio.channels.{AsynchronousServerSocketChannel => NIOServerChannel,
                          AsynchronousSocketChannel => NIOChannel,
                          AsynchronousChannelGroup}

import scala.annotation.tailrec
import blaze.pipeline.PipelineBuilder
import java.nio.ByteBuffer
import pipeline.Command.Connected
import pipeline.stages.ByteBufferHead
import com.typesafe.scalalogging.slf4j.Logging
import java.util.Date


/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
class ServerChannelFactory(pipeFactory: PipeFactory, group: AsynchronousChannelGroup = null)
        extends Logging {

  // Intended to be overridden in order to allow the reject of connections
  protected def acceptConnection(channel: NIOChannel): Boolean = true

  def bind(localAddress: SocketAddress = null): ServerChannel = {
    if (pipeFactory == null) sys.error("Pipeline factory required")
    new ServerChannel(NIOServerChannel.open(group).bind(localAddress))
  }
  
  private def root(ch: NIOChannel): PipelineBuilder[ByteBuffer, ByteBuffer] = {
    val root = new ByteBufferHead(ch)
    PipelineBuilder(root)
  }

  class ServerChannel private[ServerChannelFactory](channel: NIOServerChannel)
                extends Runnable{

    def close(): Unit = channel.close()

    def runAsync(): Unit = {
      logger.trace("Starting server loop on separate thread")
      new Thread(this).start()
    }

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
