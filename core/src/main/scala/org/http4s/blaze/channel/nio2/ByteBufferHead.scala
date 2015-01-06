package org.http4s.blaze.channel.nio2


import org.http4s.blaze.channel.ChannelHead
import org.http4s.blaze.pipeline.Command._
import org.http4s.blaze.util.BufferTools

import scala.concurrent.{Promise, Future}
import scala.annotation.tailrec

import java.nio.channels._
import java.nio.ByteBuffer
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit
import java.lang.{Long => JLong}

final class ByteBufferHead(channel: AsynchronousSocketChannel,
                        bufferSize: Int = 8*1024) extends ChannelHead {

  def name: String = "ByteBufferHeadStage"

  private val buffer = BufferTools.allocate(bufferSize)

  final override def writeRequest(data: ByteBuffer): Future[Unit] = {

    if (!data.hasRemaining() && data.position > 0) {
      logger.warn("Received write request with non-zero position but ZERO available" +
                 s"bytes at ${new Date} on org.http4s.blaze.channel $channel: $data")
      return Future.successful(())
    }

    val p = Promise[Unit]

    def go(i: Int) {
      channel.write(data, null: Null, new CompletionHandler[Integer, Null] {
        def failed(exc: Throwable, attachment: Null): Unit = {
          val e = checkError(exc)
          sendInboundCommand(Disconnected)
          closeWithError(e)
          p.tryFailure(e)
        }

        def completed(result: Integer, attachment: Null) {
          if (result.intValue < i) go(i - result.intValue)  // try to write again
          else p.trySuccess(())      // All done
        }
      })
    }
    go(data.remaining())

    p.future
  }

  final override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] = {

    val p = Promise[Unit]
    val srcs = data.toArray

    def go(index: Int): Unit = {
      channel.write[Null](srcs, index, srcs.length - index, -1L, TimeUnit.MILLISECONDS, null: Null, new CompletionHandler[JLong, Null] {
        def failed(exc: Throwable, attachment: Null) {
          val e = checkError(exc)
          sendInboundCommand(Disconnected)
          closeWithError(e)
          p.tryFailure(e)
        }

        def completed(result: JLong, attachment: Null) {
          if (BufferTools.checkEmpty(srcs)) p.trySuccess(())
          else go(BufferTools.dropEmpty(srcs))
        }
      })
    }
    go(0)

    p.future
  }

  def readRequest(size: Int): Future[ByteBuffer] = {
      
    val p = Promise[ByteBuffer]

    buffer.clear()

    if (size >= 0 && size < bufferSize)
      buffer.limit(size)

    channel.read(buffer, null: Null, new CompletionHandler[Integer, Null] {
      def failed(exc: Throwable, attachment: Null): Unit = {
        val e = checkError(exc)
        sendInboundCommand(Disconnected)
        closeWithError(e)
        p.tryFailure(e)
      }

      def completed(i: Integer, attachment: Null) {
        if (i.intValue() >= 0) {
          buffer.flip()
          val b = BufferTools.allocate(buffer.remaining())
          b.put(buffer).flip()
          p.trySuccess(b)
        } else {   // must be end of stream
          sendInboundCommand(Disconnected)
          closeWithError(EOF)
          p.tryFailure(EOF)
        }
      }
    })
    
    p.future
  }

  override protected def checkError(e: Throwable): Throwable = e match {
    case e: ShutdownChannelGroupException =>
      logger.debug(e)("Channel Group was shutdown")
      EOF

    case e: Throwable => super.checkError(e)
  }

  override protected def stageShutdown(): Unit = closeWithError(EOF)

  override protected def closeWithError(t: Throwable): Unit = {
    t match {
      case EOF => logger.debug(s"closeWithError(EOF)")
      case t   => logger.error(t)("NIO2 channel closed with unexpected error")
    }

    try channel.close()
    catch { case e: IOException => /* Don't care */ }
  }
}
