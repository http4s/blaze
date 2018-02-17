package org.http4s.blaze.channel.nio1

import java.nio.channels._
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import org.http4s.blaze.channel.{
  BufferPipelineBuilder,
  ChannelOptions,
  DefaultPoolSize,
  ServerChannel,
  ServerChannelGroup
}
import org.http4s.blaze.util
import org.log4s._

import scala.util.Try
import scala.util.control.NonFatal

object NIO1SocketServerGroup {

  /** Default size of buffer to use in a [[SelectorLoop]] */
  private val DefaultBufferSize: Int = 64 * 1024

  /** Create a new [[NIO1SocketServerGroup]] from the [[SelectorLoopPool]]. */
  def apply(
      pool: SelectorLoopPool,
      channelOptions: ChannelOptions = ChannelOptions.DefaultOptions
  ): NIO1SocketServerGroup =
    new NIO1SocketServerGroup(pool, channelOptions)

  /** Create a new [[NIO1SocketServerGroup]] with a fresh [[FixedSelectorPool]] */
  def fixedGroup(
      workerThreads: Int = DefaultPoolSize,
      bufferSize: Int = DefaultBufferSize,
      channelOptions: ChannelOptions = ChannelOptions.DefaultOptions
  ): NIO1SocketServerGroup = {
    val pool = new FixedSelectorPool(workerThreads, bufferSize)
    apply(pool, channelOptions)
  }
}

/** A thread resource group for NIO1 network operations
  *
  * @param pool [[SelectorLoopPool]] that will belong to this group. The group assumes responsibility
  *            for shutting it down. Shutting down the pool after giving it to this group will result
  *            in undefined behavior.
  */
final class NIO1SocketServerGroup private (pool: SelectorLoopPool, channelOptions: ChannelOptions)
    extends ServerChannelGroup {

  @volatile
  private[this] var isClosed = false
  private[this] val logger = getLogger
  private[this] val selector = Selector.open()
  private val t = new AcceptThread()

  // Start the selector thread
  t.setDaemon(true)
  t.start()

  override def closeGroup(): Unit = {
    val wake = synchronized {
      if (isClosed) false
      else {
        isClosed = true
        true
      }
    }

    if (wake) {
      logger.info("Closing NIO1SocketServerGroup")
      selector.wakeup()
      ()
    }
  }

  /** Create a [[org.http4s.blaze.channel.ServerChannel]] that will serve the
    * services on the requisite sockets */
  override def bind(
      address: InetSocketAddress,
      service: BufferPipelineBuilder
  ): Try[ServerChannel] = Try {
    val ch = ServerSocketChannel.open().bind(address)
    val serverChannel = new ServerChannelImpl(ch, service)
    t.listenOnChannel(serverChannel)

    logger.info("Service bound to address " + ch.getLocalAddress)
    serverChannel
  }

  private class AcceptThread extends Thread("blaze-nio1-acceptor") {
    private[this] val newChannels = new AtomicReference[List[ServerChannelImpl]](Nil)

    /** Maybe add a channel to the selector loop
      *
      * If the group is closed the channel is closed.
      */
    def listenOnChannel(channel: ServerChannelImpl): Unit = {
      def go(): Unit = newChannels.get() match {
        case null => channel.close() // Queue is closed.
        case q if newChannels.compareAndSet(q, channel :: q) =>
          // Successful set. Wake the loop.
          selector.wakeup()
          ()
        case _ =>
          go() // Lost race. Try again.
      }

      go()
    }

    override def run(): Unit = {
      acceptLoop()
      shutdown()
    }

    // Loop that continues until it detects that the channel is closed
    private[this] def acceptLoop(): Unit =
      while (!isClosed) {
        // Wait for connections or events, potentially blocking the thread.
        selector.select()

        // Add any new connections
        addNewChannels()

        val it = selector.selectedKeys.iterator()

        while (it.hasNext()) {
          val key = it.next()
          it.remove()

          // If it's not valid, the channel might have been closed
          // via the `ServerChannelImpl` or in `channelAccept`.
          if (key.isValid) {
            val channel = key.attachment.asInstanceOf[ServerChannelImpl]
            channelAccept(channel)
          }
        }
      }

    private[this] def channelAccept(channel: ServerChannelImpl): Unit = {
      val serverSocketChannel = channel.serverSocketChannel
      try {
        val clientChannel = serverSocketChannel.accept()
        if (clientChannel != null) {
          handleClientChannel(clientChannel, channel.service)
        } else {
          // This should never happen since we explicitly
          // waited for the channel to open and there isn't
          // a way for users to close the server channels
          val msg = s"While attempting to open client socket found server channel unready"
          logger.error(util.bug(msg))(msg)
        }
      } catch {
        case NonFatal(e) =>
          val localAddress = serverSocketChannel.getLocalAddress
          logger.error(e)(s"Error accepting connection on address $localAddress")

          // If the server channel cannot go on, disconnect it.
          if (!serverSocketChannel.isOpen) {
            logger.error(s"Channel bound to address $localAddress has been unexpectedly closed.")
            channel.close()
          }

        case t: Throwable =>
          logger.error(t)("Fatal error in connection accept loop. Closing Group.")
          closeGroup() // will cause the closing of the attached channels
      }
    }

    private[this] def handleClientChannel(
        clientChannel: SocketChannel,
        service: BufferPipelineBuilder
    ): Unit = {
      val address = clientChannel.getRemoteAddress.asInstanceOf[InetSocketAddress]
      // Check to see if we want to keep this connection.
      // Note: we only log at trace since it is highly likely that `acceptConnection`
      // would have also logged this info for normal application observability.
      if (acceptConnection(address)) {
        logger.trace(s"Accepted connection from $address")
        channelOptions.applyToChannel(clientChannel)
        val loop = pool.nextLoop()
        loop.initChannel(
          service,
          clientChannel,
          key => new SocketChannelHead(clientChannel, loop, key))
      } else {
        logger.trace(s"Rejected connection from $address")
        clientChannel.close()
      }
    }

    private[this] def addNewChannels(): Unit = {
      val pending = newChannels.getAndSet(Nil)

      pending.foreach { ch =>
        try {
          ch.serverSocketChannel.configureBlocking(false)
          ch.serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT, ch)

        } catch {
          case NonFatal(t) =>
            logger.error(t)(
              "Error during channel registration: " +
                ch.serverSocketChannel.getLocalAddress())
            try ch.close()
            catch {
              case NonFatal(t) =>
                logger.debug(t)("Failure during channel close")
            }
        }
      }
    }

    // Cleans up the selector and any pending new channels.
    private[this] def shutdown(): Unit = {
      // We have been closed. Close all the attached channels as well
      val it = selector.keys().iterator()
      while (it.hasNext()) {
        val key = it.next()
        val ch = key.attachment().asInstanceOf[ServerChannelImpl]
        key.cancel()
        ch.close()
      }

      // Close down the selector loops
      pool.shutdown()

      // clear out the queue
      newChannels.getAndSet(null).foreach { ch =>
        try ch.close()
        catch {
          case NonFatal(t) => logger.debug(t)("Failure during channel close")
        }
      }

      // Finally close the selector
      try selector.close()
      catch {
        case NonFatal(t) => logger.debug(t)("Failure during selector close")
      }
    }
  } // thread
}
