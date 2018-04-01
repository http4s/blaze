package org.http4s.blaze.channel.nio2

import org.http4s.blaze.channel.ChannelHead
import org.http4s.blaze.pipeline.Command._
import org.http4s.blaze.util.BufferTools

import scala.concurrent.{Future, Promise}

import java.nio.channels._
import java.nio.ByteBuffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.lang.{Long => JLong}

private[nio2] final class ByteBufferHead(channel: AsynchronousSocketChannel, bufferSize: Int)
    extends ChannelHead {

  def name: String = "ByteBufferHeadStage"

  @volatile
  private[this] var closeReason: Throwable = null
  private[this] val scratchBuffer = ByteBuffer.allocateDirect(bufferSize)

  override def writeRequest(data: ByteBuffer): Future[Unit] =
    writeRequest(data :: Nil)

  override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] = {
    val reason = closeReason
    if (reason != null) Future.failed(reason)
    else if (data.isEmpty) Future.successful(())
    else {
      val p = Promise[Unit]
      val srcs = data.toArray

      def go(index: Int): Unit =
        channel.write[Null](
          srcs,
          index,
          srcs.length - index,
          -1L,
          TimeUnit.MILLISECONDS,
          null: Null,
          new CompletionHandler[JLong, Null] {
            def failed(exc: Throwable, attachment: Null): Unit = {
              val e = checkError(exc)
              p.tryFailure(e)
              ()
            }

            def completed(result: JLong, attachment: Null): Unit =
              if (!BufferTools.checkEmpty(srcs)) go(BufferTools.dropEmpty(srcs))
              else {
                p.success(())
                ()
              }
          }
        )

      go(0)

      p.future
    }
  }

  def readRequest(size: Int): Future[ByteBuffer] = {
    val p = Promise[ByteBuffer]
    scratchBuffer.clear()

    if (size >= 0 && size < bufferSize) {
      scratchBuffer.limit(size)
    }

    channel.read(
      scratchBuffer,
      null: Null,
      new CompletionHandler[Integer, Null] {
        def failed(exc: Throwable, attachment: Null): Unit = {
          val e = checkError(exc)
          p.failure(e)
          ()
        }

        def completed(i: Integer, attachment: Null): Unit = i.intValue match {
          case 0 =>
            p.success(BufferTools.emptyBuffer)
            ()

          case i if i < 0 =>
            p.failure(EOF)

          case _ =>
            scratchBuffer.flip()
            p.success(BufferTools.copyBuffer(scratchBuffer))
            ()
        }
      }
    )
    p.future
  }

  override protected def checkError(e: Throwable): Throwable = e match {
    case e: ShutdownChannelGroupException =>
      logger.debug(e)("Channel Group was shutdown")
      EOF

    case e: Throwable => super.checkError(e)
  }

  override protected def closeWithError(t: Throwable): Unit = {
    val needsClose = synchronized {
      val reason = closeReason
      if (reason == null || reason == EOF) {
        closeReason = t
      }
      reason == null
    }

    t match {
      case EOF => logger.debug(s"closeWithError(EOF)")
      case t => logger.error(t)("NIO2 channel closed with an unexpected error")
    }

    if (needsClose) {
      try channel.close()
      catch { case e: IOException => /* Don't care */ }
    }
  }
}
