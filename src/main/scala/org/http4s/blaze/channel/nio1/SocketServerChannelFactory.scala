package org.http4s.blaze.channel.nio1

import org.http4s.blaze.channel.BufferPipeline
import java.nio.channels._
import java.net.SocketAddress
import java.io.IOException
import java.nio.ByteBuffer
import scala.util.{Failure, Success, Try}
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.channel.nio1.ChannelOps.{ChannelClosed, Complete, Incomplete, WriteResult}

/**
 * @author Bryce Anderson
 *         Created on 1/21/14
 */
class SocketServerChannelFactory(pipeFactory: BufferPipeline, pool: SelectorLoopPool)
                extends NIOServerChannelFactory[ServerSocketChannel](pool) {

  def this(pipeFactory: BufferPipeline, workerThreads: Int = 8, bufferSize: Int = 4*1024) = {
    this(pipeFactory, new FixedArraySelectorPool(workerThreads, bufferSize))
  }

  def doBind(address: SocketAddress): ServerSocketChannel = ServerSocketChannel.open().bind(address)

  def acceptConnection(serverChannel: ServerSocketChannel, loop: SelectorLoop): Boolean = {
    try {
      val ch = serverChannel.accept()
      ch.setOption(java.net.StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.FALSE)
      loop.initChannel(pipeFactory, ch, key => new SocketChannelOps(ch, loop, key))
      true
    }
    catch {case e: IOException => false }
  }

  private class SocketChannelOps(val ch: SocketChannel, val loop: SelectorLoop, val key: SelectionKey)
              extends ChannelOps {

    def performRead(scratch: ByteBuffer): Try[ByteBuffer] = {
      try {
        scratch.clear()
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

        case e: IOException if e.getMessage == "Connection reset by peer" =>
          Failure(EOF)

        case e: IOException => Failure(e)
      }
    }

    def performWrite(scratch: ByteBuffer, buffers: Array[ByteBuffer]): WriteResult = {
      logger.trace("Performing write: " + buffers)
      try {
        ch.write(buffers)
        if (buffers(buffers.length - 1).hasRemaining) Incomplete
        else Complete
      }
      catch {
        case e: ClosedChannelException =>
          ChannelClosed

        // Weird problem with windows
        case e: IOException if e.getMessage == "An existing connection was forcibly closed by the remote host" =>
          ChannelClosed
      }
    }
  }
}
