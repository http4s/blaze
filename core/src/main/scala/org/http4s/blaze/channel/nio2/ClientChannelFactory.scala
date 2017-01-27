package org.http4s.blaze.channel.nio2

import org.http4s.blaze.pipeline.HeadStage
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousSocketChannel, CompletionHandler}
import java.net.SocketAddress

import org.http4s.blaze.channel.ChannelOptions

import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal


/** A factory for opening TCP connections to remote sockets
  *
  * Provides a way to easily make TCP connections which can then serve as the Head for a pipeline
  *
  * @param bufferSize default buffer size to perform reads
  * @param group The [[java.nio.channels.AsynchronousChannelGroup]] which will manage the connection.
  *              `None` will use the system default
  */
final class ClientChannelFactory(
    bufferSize: Int = DefaultBufferSize,
    group: Option[AsynchronousChannelGroup] = None,
    channelOptions: ChannelOptions = ChannelOptions.DefaultOptions) {

  def connect(remoteAddress: SocketAddress, bufferSize: Int = bufferSize): Future[HeadStage[ByteBuffer]] = {
    val p = Promise[HeadStage[ByteBuffer]]

    try {
      val ch = AsynchronousSocketChannel.open(group.orNull)
      ch.connect(remoteAddress, null: Null, new CompletionHandler[Void, Null] {
        def failed(exc: Throwable, attachment: Null) {
          p.failure(exc)
        }

        def completed(result: Void, attachment: Null) {
          channelOptions.applyToChannel(ch)
          p.success(new ByteBufferHead(ch, bufferSize = bufferSize))
        }
      })
    }
    catch { case NonFatal(t) => p.tryFailure(t) }

    p.future
  }
}
