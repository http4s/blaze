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
  private[this] val buffer = ByteBuffer.allocateDirect(bufferSize)

  override def writeRequest(data: ByteBuffer): Future[Unit] = {
    val reason = closeReason
    if (reason != null) Future.failed(reason)
    else if (!data.hasRemaining) Future.successful(())
    else {
      val p = Promise[Unit]
      def go(i: Int): Unit =
        channel.write(
          data,
          null: Null,
          new CompletionHandler[Integer, Null] {
            def failed(exc: Throwable, attachment: Null): Unit = {
              val e = checkError(exc)
              closeWithError(e)
              p.failure(e)
              ()
            }

            def completed(result: Integer, attachment: Null): Unit =
              if (result.intValue < i) go(i - result.intValue) // try to write again
              else {
                // All done
                p.success(())
                ()
              }
          }
        )
      go(data.remaining())

      p.future
    }
  }

  override def writeRequest(data: Seq[ByteBuffer]): Future[Unit] = {
    val reason = closeReason
    if (closeReason != null) Future.failed(reason)
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
              closeWithError(e)
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
    buffer.clear()

    if (size >= 0 && size < bufferSize) {
      buffer.limit(size)
    }

    channel.read(
      buffer,
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

          case i =>
            buffer.flip()
            val b = ByteBuffer.allocate(buffer.remaining)
            b.put(buffer).flip()
            p.success(b)
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

  override protected def stageShutdown(): Unit = closeWithError(EOF)

  override protected def closeWithError(t: Throwable): Unit = {
    val needsClose = synchronized {
      val reason = closeReason
      if (reason == null || closeReason == EOF) {
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
