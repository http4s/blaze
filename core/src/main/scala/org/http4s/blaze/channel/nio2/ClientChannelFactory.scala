/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.channel.nio2

import org.http4s.blaze.pipeline.HeadStage
import java.nio.ByteBuffer
import java.nio.channels.{AsynchronousChannelGroup, AsynchronousSocketChannel, CompletionHandler}
import java.net.{SocketAddress, SocketTimeoutException}

import org.http4s.blaze.channel.ChannelOptions
import org.http4s.blaze.util.{Execution, TickWheelExecutor}
import org.log4s.getLogger

import scala.concurrent.duration.Duration
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
    connectTimeout: Duration = Duration.Inf) {
  private[this] val logger = getLogger

  // for binary compatibility with <=0.14.6
  def this(
      bufferSize: Int,
      group: Option[AsynchronousChannelGroup],
      channelOptions: ChannelOptions) =
    this(bufferSize, group, channelOptions, Execution.scheduler, Duration.Inf)

  def connect(
      remoteAddress: SocketAddress,
      bufferSize: Int = bufferSize): Future[HeadStage[ByteBuffer]] = {
    val p = Promise[HeadStage[ByteBuffer]]

    try {
      val ch = AsynchronousSocketChannel.open(group.orNull)

      val onTimeout = new Runnable {
        override def run(): Unit = {
          val exception = new SocketTimeoutException(
            s"An attempt to establish connection with $remoteAddress timed out after $connectTimeout.")
          val finishedWithTimeout = p.tryFailure(exception)
          if (finishedWithTimeout)
            try ch.close()
            catch { case NonFatal(_) => /* we don't care */ }
        }
      }
      val scheduledTimeout = scheduler.schedule(onTimeout, connectTimeout)

      val completionHandler = new CompletionHandler[Void, Null] {
        def failed(exc: Throwable, attachment: Null): Unit = {
          p.tryFailure(exc)
          scheduledTimeout.cancel()
        }

        def completed(result: Void, attachment: Null): Unit = {
          channelOptions.applyToChannel(ch)
          p.trySuccess(new ByteBufferHead(ch, bufferSize = bufferSize))
          scheduledTimeout.cancel()
        }
      }

      try ch.connect(remoteAddress, null: Null, completionHandler)
      catch {
        case ex: IllegalArgumentException =>
          try ch.close()
          catch {
            case NonFatal(e) => logger.error(e)("Failure occurred while closing channel.")
          }
          throw ex
      }
    } catch { case NonFatal(t) => p.tryFailure(t) }

    p.future
  }
}
