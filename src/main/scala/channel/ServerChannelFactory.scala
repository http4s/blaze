package channel

import java.net.SocketAddress
import scala.concurrent.ExecutionContext
import java.nio.channels.{AsynchronousServerSocketChannel => NIOServerChannel, AsynchronousSocketChannel => NIOChannel, AsynchronousChannelGroup}
import java.util.concurrent.{Future => JFuture}
import scala.annotation.tailrec
import pipeline.HeadStage
import java.nio.ByteBuffer
import pipeline.Command.Connected


/**
 * @author Bryce Anderson
 *         Created on 1/4/14
 */
class ServerChannelFactory {

  private var pipeFactory: NIOChannel => HeadStage[ByteBuffer] = null
  private var group: AsynchronousChannelGroup = null

  protected def acceptConnection(channel: NIOChannel): Boolean = true

  def pipelineFactory(f: NIOChannel => HeadStage[ByteBuffer]): this.type = {
    pipeFactory = f
    this
  }
  
  def withGroup(g: AsynchronousChannelGroup): this.type = { group = g; this }

  def bind(localAddress: SocketAddress = null): ServerChannel = {
    if (pipeFactory == null) sys.error("Pipeline factory required")
    new ServerChannel(NIOServerChannel.open(group).bind(localAddress))
  }


  class ServerChannel private[ServerChannelFactory](channel: NIOServerChannel)
                extends Runnable{

    def close(): Unit = channel.close()

    def runAsync(): Unit = new Thread(this).start()

    @tailrec
    final def run():Unit = {
      if (channel.isOpen) {
        var continue = true
        try {
          val ch = channel.accept().get() // Will synchronize here

          if (!acceptConnection(ch)) ch.close()
          else pipeFactory(ch).inboundCommand(Connected)

        } catch {
          case e: InterruptedException => continue = false

        }
        if (continue) run()
      }
      else sys.error("Channel closed")
    }
  }
}
