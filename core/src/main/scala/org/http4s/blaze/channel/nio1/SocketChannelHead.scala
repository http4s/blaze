package org.http4s.blaze.channel.nio1

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.{ClosedChannelException, SelectionKey, SocketChannel}

import org.http4s.blaze.channel.ChannelHead.brokePipeMessages
import org.http4s.blaze.channel.nio1.NIO1HeadStage.{Complete, Incomplete, WriteError, WriteResult}
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.util
import org.http4s.blaze.util.BufferTools

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

/** Implementation of the channel head that can deal explicitly with a SocketChannel */
private class SocketChannelHead(ch: SocketChannel, loop: SelectorLoop, key: SelectionKey)
    extends NIO1HeadStage(ch, loop, key) {
  override protected def performRead(scratch: ByteBuffer): Try[ByteBuffer] =
    try {
      scratch.clear()
      val bytes = ch.read(scratch)
      if (bytes >= 0) {
        scratch.flip()

        val b = ByteBuffer.allocate(scratch.remaining())
        b.put(scratch)
        b.flip()
        Success(b)
      } else Failure(EOF)

    } catch {
      case e: ClosedChannelException => Failure(EOF)
      case e: IOException if brokePipeMessages.contains(e.getMessage) =>
        Failure(EOF)
      case e: IOException => Failure(e)
    }

  override protected def performWrite(
      scratch: ByteBuffer,
      buffers: Array[ByteBuffer]): WriteResult =
    try {
      if (BufferTools.areDirectOrEmpty(buffers)) {
        ch.write(buffers)
        if (util.BufferTools.checkEmpty(buffers)) Complete
        else Incomplete
      } else {
        // To sidestep the java NIO "memory leak" (see http://www.evanjones.ca/java-bytebuffer-leak.html)
        // We copy the data to the scratch buffer (which should be a direct ByteBuffer)
        // before the write. We then check to see how much data was written and fast-forward
        // the input buffers accordingly.
        // This is very similar to the pattern used by the Oracle JDK implementation in its
        // IOUtil class: if the provided buffers are not direct buffers, they are copied to
        // temporary direct ByteBuffers and written.
        @tailrec
        def writeLoop(): WriteResult = {
          scratch.clear()
          BufferTools.copyBuffers(buffers, scratch)
          scratch.flip()

          val written = ch.write(scratch)
          if (written > 0) {
            assert(BufferTools.fastForwardBuffers(buffers, written))
          }

          if (scratch.remaining() > 0) {
            // Couldn't write all the data
            Incomplete
          } else if (util.BufferTools.checkEmpty(buffers)) {
            // All data was written
            Complete
          } else {
            // May still be able to write more to the socket buffer
            writeLoop()
          }
        }

        writeLoop()
      }
    } catch {
      case _: ClosedChannelException => WriteError(EOF)
      case e: IOException if brokePipeMessages.contains(e.getMessage) =>
        WriteError(EOF)
      case e: IOException =>
        logger.warn(e)("Error writing to channel")
        WriteError(e)
    }
}
