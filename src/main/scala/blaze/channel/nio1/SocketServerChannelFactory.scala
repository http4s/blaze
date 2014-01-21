package blaze.channel.nio1

import blaze.channel.PipeFactory
import java.nio.channels.{AsynchronousCloseException, ClosedChannelException, SocketChannel, ServerSocketChannel}
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
                extends NIOServerChannelFactory(pipeFactory, bufferSize, workerThreads ) {

  type Channel = ServerSocketChannel


  def doBind(address: SocketAddress): Channel = ServerSocketChannel.open().bind(address)

  def accept(ch: Channel): Option[ChannelOps] = {
    try Some(new SocketChannelOps(ch.accept()))
    catch {case e: IOException => None }
  }

  private class SocketChannelOps(val ch: SocketChannel) extends ChannelOps {
    def performRead(scratch: ByteBuffer): Try[ByteBuffer] = {
      try {
        ch.read(scratch)
        scratch.flip()

        val b = ByteBuffer.allocate(scratch.remaining())
        b.put(scratch)
        b.flip()
        Success(b)
      }
      catch {
        case e: ClosedChannelException => Failure(EOF)
        case e: AsynchronousCloseException => Failure(EOF)
        case e: IOException => Failure(e)
      }
    }

    def performWrite(buffers: Array[ByteBuffer]): Try[Any] = {
      try {
        ch.write(buffers)
        if (buffers(buffers.length - 1).hasRemaining) null
        else Success()
      }
      catch {
        case e: ClosedChannelException => Failure(EOF)
        case e: AsynchronousCloseException => Failure(EOF)
        case e: IOException => Failure(e)
      }
    }
  }
}
