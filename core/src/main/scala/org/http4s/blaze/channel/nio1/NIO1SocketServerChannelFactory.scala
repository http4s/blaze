package org.http4s.blaze
package channel.nio1

import org.http4s.blaze.channel.BufferPipelineBuilder
import java.nio.channels._
import java.net.SocketAddress
import java.io.IOException
import java.nio.ByteBuffer
import org.http4s.blaze.util.BufferTools

import scala.util.{Failure, Success, Try}
import org.http4s.blaze.pipeline.Command.EOF
import NIO1HeadStage._

class NIO1SocketServerChannelFactory(pipeFactory: BufferPipelineBuilder,
                                     pool: SelectorLoopPool)
          extends NIO1ServerChannelFactory[ServerSocketChannel](pool)
{

  import org.http4s.blaze.channel.ChannelHead.brokePipeMessages

  def this(pipeFactory: BufferPipelineBuilder, workerThreads: Int = 8, bufferSize: Int = 4*1024) =
    this(pipeFactory, new FixedSelectorPool(workerThreads, bufferSize))

  //////////////// End of constructors /////////////////////////////////////////////////////////

  def doBind(address: SocketAddress): ServerSocketChannel = ServerSocketChannel.open().bind(address)

  override def completeConnection(serverChannel: ServerSocketChannel, loop: SelectorLoop): Boolean = {
    try {
      val ch = serverChannel.accept()
      val addr = ch.getRemoteAddress

      // check to see if we want to keep this connection
      if (doAcceptConnection(addr)) {
        ch.setOption(java.net.StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.FALSE)
        loop.initChannel(pipeFactory, ch, key => new SocketChannelHead(ch, loop, key))
        true
      } else {
        ch.close()
        false
      }

    } catch {
      case e: IOException => false
    }
  }

  private class SocketChannelHead(ch: SocketChannel,
                                loop: SelectorLoop,
                                 key: SelectionKey) extends NIO1HeadStage(ch, loop, key)
  {
    override def performRead(scratch: ByteBuffer): Try[ByteBuffer] = {
      try {
        scratch.clear()
        val bytes = ch.read(scratch)
        if (bytes >= 0) {
          scratch.flip()

          val b = BufferTools.allocate(scratch.remaining())
          b.put(scratch)
          b.flip()
          Success(b)
        }
        else Failure(EOF)

      } catch {
        case e: ClosedChannelException => Failure(EOF)
        case e: IOException if brokePipeMessages.contains(e.getMessage) => Failure(EOF)
        case e: IOException => Failure(e)
      }
    }

    override def performWrite(scratch: ByteBuffer, buffers: Array[ByteBuffer]): WriteResult = {
      try {
        ch.write(buffers)
        if (util.BufferTools.checkEmpty(buffers)) Complete
        else Incomplete
      }
      catch {
        case e: ClosedChannelException => WriteError(EOF)
        case e: IOException if brokePipeMessages.contains(e.getMessage) => WriteError(EOF)
        case e: IOException =>
          logger.warn(e)("Error writing to channel")
          WriteError(e)
      }
    }
  }
}

