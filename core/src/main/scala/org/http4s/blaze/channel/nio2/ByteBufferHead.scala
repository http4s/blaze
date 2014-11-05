package org.http4s.blaze.channel.nio2


import org.http4s.blaze.pipeline.HeadStage
import org.http4s.blaze.pipeline.Command._
import org.http4s.blaze.pipeline.Command.Error

import scala.concurrent.{Promise, Future}
import scala.annotation.tailrec

import java.nio.channels._
import java.nio.ByteBuffer
import java.io.IOException
import java.util.Date
import java.util.concurrent.TimeUnit
import java.lang.{Long => JLong}
import org.log4s.getLogger

class ByteBufferHead(channel: AsynchronousSocketChannel,
                     val name: String = "ByteBufferHeadStage",
                     bufferSize: Int = 8*1024) extends HeadStage[ByteBuffer] {
  private[this] val logger = getLogger

  private val buffer = ByteBuffer.allocate(bufferSize)

  def writeRequest(data: ByteBuffer): Future[Unit] = {

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

  override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] = {

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
          if (exc.isInstanceOf[ClosedChannelException]) logger.trace("Channel closed, dropping packet")
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
          val b = ByteBuffer.allocate(buffer.remaining())
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

  override def stageShutdown(): Unit = closeChannel()

  override def outboundCommand(cmd: OutboundCommand): Unit = cmd match {
    case Disconnect         => closeChannel()
    case Error(e)         => logger.error(e)("ByteBufferHead received error command"); channelError(e)
    case cmd              => // NOOP
  }

  /////////////////////////// Private methods /////////////////////////////////////////

  private def checkError(e: Throwable): Throwable = e match {
    case e: ClosedChannelException =>
      logger.trace("Channel closed, dropping packet")
      closeChannel()
      EOF

    case e: IOException =>
      logger.trace(e)("Channel IO Error. Closing")
      closeChannel()
      EOF

    case e: ShutdownChannelGroupException =>
      logger.trace(e)("Channel Group was shutdown")
      closeChannel()
      EOF

    case e: Throwable =>  // Don't know what to do besides close
      channelError(e)
      e
  }

  private def channelError(e: Throwable) {
    logger.error(e)("Unexpected fatal error")
    sendInboundCommand(Error(e))
    closeChannel()
  }

  private def closeChannel() {
    logger.trace("channelClose")
    try channel.close()
    catch {  case e: IOException => /* Don't care */ }
  }
}
