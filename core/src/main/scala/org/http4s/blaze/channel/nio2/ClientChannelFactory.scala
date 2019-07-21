package org.http4s.blaze.channel.nio2

import org.http4s.blaze.pipeline.HeadStage
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousSocketChannel, CompletionHandler}
import java.net.{SocketAddress, SocketTimeoutException}

import org.http4s.blaze.channel.ChannelOptions
import org.http4s.blaze.util.{Execution, TickWheelExecutor}

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

/** A factory for opening TCP connections to remote sockets
  *
  * Provides a way to easily make TCP connections which can then serve as the Head for a pipeline
  *
  * @param bufferSize default buffer size to perform reads
  * @param group The `AsynchronousChannelGroup` which will manage the connection.
  *              `None` will use the system default
  */
final class ClientChannelFactory(
    bufferSize: Int = DefaultBufferSize,
    group: Option[AsynchronousChannelGroup] = None,
    channelOptions: ChannelOptions = ChannelOptions.DefaultOptions,
    scheduler: TickWheelExecutor = Execution.scheduler,
    connectingTimeout: Option[FiniteDuration] = None) {

  def connect(
      remoteAddress: SocketAddress,
      bufferSize: Int = bufferSize): Future[HeadStage[ByteBuffer]] = {
    val p = Promise[HeadStage[ByteBuffer]]

    try {
      val ch = AsynchronousSocketChannel.open(group.orNull)

      val scheduledTimeout = connectingTimeout.map { t =>
        val onTimeout: Runnable = () => {
          val finishedWithTimeout = p.tryFailure(new SocketTimeoutException())
          if (finishedWithTimeout) {
            try { ch.close() } catch { case NonFatal(_) => /* we don't care */ }
          }
        }
        scheduler.schedule(onTimeout, t)
      }

      ch.connect(
        remoteAddress,
        null: Null,
        new CompletionHandler[Void, Null] {
          def failed(exc: Throwable, attachment: Null): Unit = {
            p.tryFailure(exc)
            scheduledTimeout.foreach(_.cancel())
            ()
          }

          def completed(result: Void, attachment: Null): Unit = {
            channelOptions.applyToChannel(ch)
            p.trySuccess(new ByteBufferHead(ch, bufferSize = bufferSize))
            scheduledTimeout.foreach(_.cancel())
            ()
          }
        }
      )
    } catch { case NonFatal(t) => p.tryFailure(t) }

    p.future
  }
}
