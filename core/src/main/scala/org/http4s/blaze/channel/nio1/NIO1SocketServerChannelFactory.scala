package org.http4s.blaze
package channel.nio1

import org.http4s.blaze.util.BufferTools
import org.http4s.blaze.channel.BufferPipelineBuilder
import org.http4s.blaze.pipeline.Command.EOF

import java.nio.channels._
import java.net.SocketAddress
import java.io.IOException
import java.nio.ByteBuffer

import scala.util.{Failure, Success, Try}

import NIO1HeadStage._

object NIO1SocketServerChannelFactory {

  /** Default number of threads used to make a new [[SelectorLoopPool]] if not specified */
  val defaultPoolSize: Int = Runtime.getRuntime.availableProcessors()*2 + 1

  /** Default size of buffer to use in a [[SelectorLoop]] */
  val defaultBufferSize: Int = 16*1024

  def apply(pipeFactory: BufferPipelineBuilder, pool: SelectorLoopPool): NIO1SocketServerChannelFactory =
    new NIO1SocketServerChannelFactory(pipeFactory, pool)

  def apply(pipeFactory: BufferPipelineBuilder,
            workerThreads: Int = defaultPoolSize, bufferSize: Int = defaultBufferSize): NIO1SocketServerChannelFactory = {
    val pool = new FixedSelectorPool(workerThreads, bufferSize)
    new NIO1SocketServerChannelFactory(pipeFactory, pool)
  }


}

/** A concrete type for TCP servers */
class NIO1SocketServerChannelFactory private(pipeFactory: BufferPipelineBuilder,
                                             pool: SelectorLoopPool)
          extends NIO1ServerChannelFactory[ServerSocketChannel](pool)
{
  import org.http4s.blaze.channel.ChannelHead.brokePipeMessages

  override protected def doBind(address: SocketAddress): ServerSocketChannel =
    ServerSocketChannel.open().bind(address)

  override protected def completeConnection(serverChannel: ServerSocketChannel, loop: SelectorLoop): Boolean =
    try {
      val ch = serverChannel.accept()
      val address = ch.getRemoteAddress

      // check to see if we want to keep this connection
      if (acceptConnection(address)) {
        ch.setOption(java.net.StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.FALSE)
        loop.initChannel(pipeFactory, ch, key => new SocketChannelHead(ch, loop, key))
        true
      } else {
        ch.close()
        false
      }
    }
    catch {
      case e: IOException => false
    }

  private class SocketChannelHead(ch: SocketChannel,
                                loop: SelectorLoop,
                                 key: SelectionKey) extends NIO1HeadStage(ch, loop, key)
  {
    override protected def performRead(scratch: ByteBuffer): Try[ByteBuffer] = {
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

    override protected def performWrite(scratch: ByteBuffer, buffers: Array[ByteBuffer]): WriteResult = {
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

