package blaze.channel.nio1

import blaze.channel.PipeFactory
import java.nio.channels._
import java.net.SocketAddress
import java.io.IOException
import java.nio.ByteBuffer
import scala.util.{Failure, Success, Try}
import blaze.pipeline.Command.EOF

/**
 * @author Bryce Anderson
 *         Created on 1/21/14
 */
class SocketServerChannelFactory(pipeFactory: PipeFactory, bufferSize: Int = 4*1024, workerThreads: Int = 8)
                extends NIOServerChannelFactory(bufferSize, workerThreads ) {

  type Channel = ServerSocketChannel


  def doBind(address: SocketAddress): Channel = ServerSocketChannel.open().bind(address)

  def acceptConnection(serverChannel: Channel, loop: SelectorLoop): Boolean = {
    try {
      val ch = serverChannel.accept()
      loop.initChannel(pipeFactory, ch, key => new SocketChannelOps(ch, loop, key))
      true
    }
    catch {case e: IOException => false }
  }

  private class SocketChannelOps(val ch: SocketChannel, val loop: SelectorLoop, val key: SelectionKey)
              extends ChannelOps {
    def performRead(scratch: ByteBuffer): Try[ByteBuffer] = {
      try {
        val bytes = ch.read(scratch)
        logger.trace(s"Read $bytes bytes")
        if (bytes >= 0) {
          scratch.flip()

          val b = ByteBuffer.allocate(scratch.remaining())
          b.put(scratch)
          b.flip()
          Success(b)
        }
        else Failure(EOF)

      } catch {
        case e: ClosedChannelException => Failure(EOF)
          // Weird problem with windows
        case e: IOException if e.getMessage == "An existing connection was forcibly closed by the remote host" =>
          Failure(EOF)
        case e: IOException => Failure(e)
      }
    }

    def performWrite(buffers: Array[ByteBuffer]): Try[Any] = {
      logger.trace("Performing write: " + buffers)
      try {
        ch.write(buffers)
        if (buffers(buffers.length - 1).hasRemaining) null
        else Success()
      }
      catch {
        case e: ClosedChannelException => Failure(EOF)
        // Weird problem with windows
        case e: IOException if e.getMessage == "An existing connection was forcibly closed by the remote host" =>
          Failure(EOF)
        case e: IOException => Failure(e)
      }
    }
  }
}
