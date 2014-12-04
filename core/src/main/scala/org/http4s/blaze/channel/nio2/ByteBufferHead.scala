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

    val f = Promise[Unit]

    def go(i: Int) {
      channel.write(data, null: Null, new CompletionHandler[Integer, Null] {
        def failed(exc: Throwable, attachment: Null): Unit = f.tryFailure(checkError(exc))

        def completed(result: Integer, attachment: Null) {
          if (result.intValue < i) go(i - result.intValue)  // try to write again
          else f.trySuccess(())      // All done
        }
      })
    }
    go(data.remaining())

    f.future
  }

  final override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] = {

    val f = Promise[Unit]
    val srcs = data.toArray
    val sz: Long = {
      @tailrec def go(size: Long, pos: Int): Long = {
        if (pos < srcs.length) go(size + srcs(pos).remaining(), pos + 1)
        else size
      }
      go(0, 0)
    }

    def go(i: Long): Unit = {
      channel.write[Null](srcs, 0, srcs.length, -1L, TimeUnit.MILLISECONDS, null: Null, new CompletionHandler[JLong, Null] {
        def failed(exc: Throwable, attachment: Null) {
          if (exc.isInstanceOf[ClosedChannelException]) logger.debug("Channel closed, dropping packet")
          else logger.error(exc)("Failure writing to channel")
          f.tryFailure(exc)
        }

        def completed(result: JLong, attachment: Null) {
          if (result.longValue < i) go(i - result.longValue)  // try to write again
          else f.trySuccess(())      // All done
        }
      })
    }
    go(sz)

    f.future
  }

  def readRequest(size: Int): Future[ByteBuffer] = {
      
    val p = Promise[ByteBuffer]

    buffer.clear()

    if (size >= 0 && size < bufferSize)
      buffer.limit(size)

    channel.read(buffer, null: Null, new CompletionHandler[Integer, Null] {
      def failed(exc: Throwable, attachment: Null): Unit = p.tryFailure(checkError(exc))

      def completed(i: Integer, attachment: Null) {
        if (i.intValue() >= 0) {
          buffer.flip()
          val b = BufferTools.allocate(buffer.remaining())
          b.put(buffer).flip()
          p.trySuccess(b)
        } else {   // must be end of stream
          p.tryFailure(EOF)
          closeChannel()
        }
      }
    })
    
    p.future
  }

  override protected def checkError(e: Throwable): Throwable = e match {
    case e: ShutdownChannelGroupException =>
      logger.debug(e)("Channel Group was shutdown")
      closeChannel()
      EOF

    case e: Throwable =>  // Don't know what to do besides close
      super.checkError(e)
  }

  override protected def stageShutdown(): Unit = closeChannel()

  override protected def closeWithError(t: Throwable): Unit = {
    logger.error(t)(s"$name closing with error.")
    closeChannel()
  }

  override protected def closeChannel(): Unit = {
    logger.debug("channelClose")
    try channel.close()
    catch { case e: IOException => /* Don't care */ }
  }
}
