package org.http4s.blaze.channel.nio2

import java.net.InetSocketAddress
import java.nio.channels._
import java.util.Date
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

import org.http4s.blaze.channel._
import org.http4s.blaze.pipeline.Command.Connected

import scala.util.Try
import scala.util.control.NonFatal


object NIO2SocketServerGroup {

  /** Create a new fixed size NIO2 SocketServerGroup
    *
    * @param workerThreads number of worker threads for the new group
    * @param bufferSize buffer size use for IO operations
    * @param channelOptions options to apply to the client connections
    */
  def fixedGroup(
    workerThreads: Int = DefaultPoolSize,
    bufferSize: Int = DefaultBufferSize,
    channelOptions: ChannelOptions = ChannelOptions.DefaultOptions
  ): NIO2SocketServerGroup = {
    val factory = new ThreadFactory {
      val i = new AtomicInteger(0)
      override def newThread(r: Runnable): Thread = {
        val t = new Thread(r, "blaze-nio2-fixed-pool-" + i.getAndIncrement())
        t.setDaemon(false)
        t
      }
    }
    
    val group = AsynchronousChannelGroup.withFixedThreadPool(workerThreads, factory)
    apply(bufferSize, Some(group), channelOptions)
  }

  /** Create a new NIO2 SocketServerGroup
    *
    * @param bufferSize buffer size use for IO operations
    * @param group optional `AsynchronousChannelGroup`, uses the system default if `None`
    * @param channelOptions options to apply to the client connections
    */
  def apply(
    bufferSize: Int = 8*1024,
    group: Option[AsynchronousChannelGroup] = None,
    channelOptions: ChannelOptions = ChannelOptions.DefaultOptions
  ): NIO2SocketServerGroup =
    new NIO2SocketServerGroup(bufferSize, group.orNull, channelOptions)
}

final  class NIO2SocketServerGroup private(
    bufferSize: Int,
    group: AsynchronousChannelGroup,
    channelOptions: ChannelOptions)
  extends ServerChannelGroup {

  /** Closes the group along with all current connections.
    *
    * __WARNING:__ the default group, or the system wide group, will __NOT__ be shut down and
    * will result in an `IllegalStateException`.
    */
  override def closeGroup(): Unit = {
    if (group != null) {
      logger.info("Closing NIO2 SocketChannelServerGroup")
      group.shutdownNow()
    }
    else throw new IllegalStateException("Cannot shut down the system default AsynchronousChannelGroup.")
  }

  def bind(address: InetSocketAddress, service: BufferPipelineBuilder): Try[ServerChannel] = {
    Try {
      val ch = AsynchronousServerSocketChannel.open(group).bind(address)
      val serverChannel = new NIO2ServerChannel(ch.getLocalAddress.asInstanceOf[InetSocketAddress], ch, service)
      serverChannel.run()
      serverChannel
    }
  }

private final class NIO2ServerChannel(
    val socketAddress: InetSocketAddress,
    ch: AsynchronousServerSocketChannel,
    service: BufferPipelineBuilder)
  extends ServerChannel {

    override protected def closeChannel(): Unit =
      if (ch.isOpen()) {
        logger.info(s"Closing NIO2 channel $socketAddress at ${new Date}")
        try ch.close()
        catch { case NonFatal(t) => logger.debug(t)("Failure during channel close") }
      }

    def errorClose(e: Throwable): Unit = {
      logger.error(e)("Server socket channel closed with error.")
      normalClose()
    }

    def normalClose(): Unit = {
      try close()
      catch { case NonFatal(e) => logger.error(e)("Error on NIO2ServerChannel shutdown invoked by listen loop.") }
    }

    def run() = listen(ch, service)

    def listen(channel: AsynchronousServerSocketChannel, pipeFactory: BufferPipelineBuilder): Unit = {
      channel.accept(null: Null, new CompletionHandler[AsynchronousSocketChannel, Null] {
        override def completed(ch: AsynchronousSocketChannel, attachment: Null): Unit = {
          val address = ch.getRemoteAddress().asInstanceOf[InetSocketAddress]

          if (!acceptConnection(address)) ch.close()
          else {
            channelOptions.applyToChannel(ch)
            pipeFactory(new NIO2SocketConnection(ch))
              .base(new ByteBufferHead(ch, bufferSize))
              .sendInboundCommand(Connected)
          }

          listen(channel, pipeFactory) // Continue the circle of life
        }

        override def failed(exc: Throwable, attachment: Null): Unit = {
          exc match {
            case _: AsynchronousCloseException => normalClose()
            case _: ClosedChannelException => normalClose()
            case _: ShutdownChannelGroupException => normalClose()
            case _ =>
              logger.error(exc)(s"Error accepting connection on address $socketAddress")
              // If the server channel cannot go on, disconnect it.
              if (!channel.isOpen()) errorClose(exc)
          }
        }
      })
    }
  }
}
