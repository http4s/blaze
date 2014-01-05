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

  private var pipelineExecutor: ExecutionContext = ExecutionContext.global
  private var pipeFactory: NIOChannel => HeadStage[ByteBuffer] = null

  def pipelineFactory(f: NIOChannel => HeadStage[ByteBuffer]): this.type = {
    pipeFactory = f
    this
  }

  def withPipelineExecutor(ec: ExecutionContext): this.type = {
    assert(ec != null)
    pipelineExecutor = ec
    this
  }

  def bind(localAddress: SocketAddress = null): ServerChannel = {

    if (pipeFactory == null) sys.error("Pipeline factory required")

    new ServerChannel(NIOServerChannel.open().bind(localAddress), pipeFactory)
  }

}

class ServerChannel(channel: NIOServerChannel,
                    pipeFactory: NIOChannel => HeadStage[ByteBuffer]) {

  def close(): Unit = channel.close()

  protected def acceptConnection(channel: NIOChannel): Boolean = true

  @tailrec
  private def acceptLoop():Unit = {
    if (channel.isOpen) {
      var continue = true
      try {
        val ch = channel.accept().get() // Will synchronize here

        if (!acceptConnection(ch)) ch.close()
        else pipeFactory(ch).inboundCommand(Connected)

      } catch {
        case e: InterruptedException => continue = false

      }
      if (continue) acceptLoop()
    }
    else sys.error("Channel closed")
  }
}

class ConnectionChannel {


}
