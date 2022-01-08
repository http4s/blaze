/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.channel.nio1

import java.io.IOException
import java.nio.channels._
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.{RejectedExecutionException, ThreadFactory}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.http4s.blaze.channel.{
  ChannelOptions,
  DefaultMaxConnections,
  DefaultPoolSize,
  ServerChannel,
  ServerChannelGroup,
  SocketPipelineBuilder
}
import org.http4s.blaze.pipeline.Command
import org.http4s.blaze.util.{BasicThreadFactory, Connections}
import org.log4s._

import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

object NIO1SocketServerGroup {

  /** Default size of buffer to use in a [[SelectorLoop]] */
  private[this] val DefaultBufferSize: Int = 64 * 1024

  private[this] val acceptorNumber = new AtomicInteger(0)
  private[this] val workerNumber = new AtomicInteger(0)

  private[this] def defaultAcceptorThreadFactory: ThreadFactory = {
    val id = acceptorNumber.getAndIncrement()
    BasicThreadFactory(prefix = s"blaze-acceptor-$id", daemonThreads = false)
  }

  private[this] def defaultWorkerThreadFactory: ThreadFactory = {
    val id = workerNumber.getAndIncrement()
    BasicThreadFactory(prefix = s"blaze-worker-$id", daemonThreads = false)
  }

  /** Create a new [[NIO1SocketServerGroup]] from the [[SelectorLoopPool]].
    *
    * @note
    *   The worker pool is not owned by the group and therefore not shutdown when the group is
    *   shutdown.
    */
  def create(
      acceptorPool: SelectorLoopPool,
      workerPool: SelectorLoopPool,
      channelOptions: ChannelOptions = ChannelOptions.DefaultOptions,
      maxConnections: Int = DefaultMaxConnections
  ): ServerChannelGroup =
    new NIO1SocketServerGroup(acceptorPool, workerPool, channelOptions, maxConnections)

  /** Create a new [[NIO1SocketServerGroup]] with a fresh
    * [[FixedSelectorPool] ] The resulting [[ServerChannelGroup]] takes ownership of the created
    * pool, shutting it down when the group is shutdown.
    */
  def fixed(
      workerThreads: Int = DefaultPoolSize,
      bufferSize: Int = DefaultBufferSize,
      channelOptions: ChannelOptions = ChannelOptions.DefaultOptions,
      selectorThreadFactory: ThreadFactory = defaultWorkerThreadFactory,
      acceptorThreads: Int = 1,
      acceptorThreadFactory: ThreadFactory = defaultAcceptorThreadFactory,
      maxConnections: Int = DefaultMaxConnections
  ): ServerChannelGroup = {
    val acceptorPool = new FixedSelectorPool(acceptorThreads, 1, acceptorThreadFactory)
    val workerPool = new FixedSelectorPool(workerThreads, bufferSize, selectorThreadFactory)
    val underlying = create(acceptorPool, workerPool, channelOptions, maxConnections)

    // Proxy to the underlying group. `close` calls also close
    // the worker pools since we were the ones that created it.
    new ServerChannelGroup {
      override def closeGroup(): Unit = {
        // Closing the underlying group will schedule their
        // shutdown tasks so even though they will be executed
        // asynchronously, they will be handled before the loops
        // shutdown since they cleanup pending tasks before dying
        // themselves.
        underlying.closeGroup()
        workerPool.close()
        acceptorPool.close()
      }

      override def bind(
          address: InetSocketAddress,
          service: SocketPipelineBuilder
      ): Try[ServerChannel] =
        underlying.bind(address, service)
    }
  }
}

/** A thread resource group for NIO1 network operations
  *
  * @param workerPool
  *   [[SelectorLoopPool]] that will belong to this group. The group assumes responsibility for
  *   shutting it down. Shutting down the pool after giving it to this group will result in
  *   undefined behavior.
  */
private final class NIO1SocketServerGroup private (
    acceptorPool: SelectorLoopPool,
    workerPool: SelectorLoopPool,
    channelOptions: ChannelOptions,
    maxConnections: Int)
    extends ServerChannelGroup {
  private[this] val logger = getLogger
  // Also acts as our intrinsic lock.
  private[this] val listeningSet = new mutable.HashSet[ServerChannelImpl]()
  // protected by synchronization on the intrinsic lock.
  private[this] var isClosed = false

  private[this] val connections: Connections = Connections(maxConnections)

  // Closing delegates to the `ServerChannelImpl` which
  // ensures only-once behavior and attempts to close the
  // channel within the `SelectorLoop`, if it's still running.
  // That should help alleviate many of the exceptions associated
  // with performing operations on closed channels, etc.
  private[this] class SocketAcceptor(
      key: SelectionKey,
      ch: ServerChannelImpl,
      service: SocketPipelineBuilder)
      extends Selectable {
    // Save it since once the channel is closed, we're in trouble.
    private[this] val closed = new AtomicBoolean(false)

    override def opsReady(unused: ByteBuffer): Unit =
      if (key.isAcceptable) {
        try acceptNewConnections()
        catch {
          case ex: IOException =>
            close(Some(ex))
        }
      }

    override def close(cause: Option[Throwable]): Unit =
      if (closed.compareAndSet(false, true) && !ch.channelClosed) {
        cause match {
          case Some(c) =>
            logger.error(c)(s"Listening socket(${ch.socketAddress}) closed forcibly.")

          case None =>
            logger.info(s"Listening socket(${ch.socketAddress}) closed.")
        }
        doClose()
      }

    // To close, we close the `ServerChannelImpl` which will
    // close the `ServerSocketChannel`.
    private[this] def doClose(): Unit = ch.close()

    @tailrec
    private[this] def acceptNewConnections(): Unit = {
      // We go in a loop just in case we have more than one.
      // Once we're out, the `.accept()` method will return `null`.
      connections.acquire()
      val child = ch.selectableChannel.accept()
      if (child != null) {
        val channel = new NIO1ClientChannel(child, () => connections.release())
        handleClientChannel(channel, service)
        acceptNewConnections()
      } else {
        connections.release()
      }
    }
  }

  // The core type tracked in this group. It is exclusively responsible
  // for closing the underlying `ServerSocketChannel`. IF possible, closing
  // the underlying channel is done in its assigned selector loop to
  // minimize race conditions.
  private[this] final class ServerChannelImpl(
      val selectableChannel: ServerSocketChannel,
      selectorLoop: SelectorLoop)
      extends ServerChannel
      with NIO1Channel {
    @volatile
    private[this] var closed = false

    def channelClosed: Boolean = closed

    val socketAddress: InetSocketAddress =
      selectableChannel.getLocalAddress.asInstanceOf[InetSocketAddress]

    override protected def closeChannel(): Unit = {
      // We try to run the close in the event loop, but just
      // in case we were closed because the event loop was closed,
      // we need to be ready to handle a `RejectedExecutionException`.
      def doClose(): Unit = {
        this.logger.info(s"Closing NIO1 channel $socketAddress")
        closed = true
        listeningSet.synchronized {
          listeningSet.remove(this)
        }
        try selectableChannel.close()
        catch {
          case NonFatal(t) => this.logger.warn(t)("Failure during channel close.")
        } finally connections.close() // allow the acceptor thread through
      }

      try
        // We use `enqueueTask` deliberately so as to not jump ahead
        // of channel initialization.
        selectorLoop.enqueueTask(new Runnable {
          override def run(): Unit = doClose()
        })
      catch {
        case _: RejectedExecutionException =>
          this.logger.info("Selector loop closed. Closing in local thread.")
          doClose()
      }
    }
  }

  override def closeGroup(): Unit = {
    // Set the state to closed and close any existing acceptors
    val toClose = listeningSet.synchronized {
      if (isClosed) Seq.empty
      else {
        isClosed = true
        val toClose = listeningSet.toVector
        listeningSet.clear()
        toClose
      }
    }
    toClose.foreach(_.close())
  }

  /** Create a [[org.http4s.blaze.channel.ServerChannel]] that will serve the services on the
    * requisite sockets
    */
  override def bind(
      address: InetSocketAddress,
      service: SocketPipelineBuilder
  ): Try[ServerChannel] =
    Try {
      val ch = ServerSocketChannel.open().bind(address)
      ch.configureBlocking(false)
      val loop = acceptorPool.nextLoop()

      val serverChannel = new ServerChannelImpl(ch, loop)
      val closed = listeningSet.synchronized {
        if (isClosed) {
          true
        } else {
          listeningSet += serverChannel
          false
        }
      }

      if (closed) {
        logger.info("Group closed")
        serverChannel.close()
      } else {
        logger.info("Service bound to address " + serverChannel.socketAddress)
        loop.initChannel(serverChannel, buildSocketAcceptor(serverChannel, service))
      }

      serverChannel
    }

  // Will be called from within the SelectorLoop
  private[this] def buildSocketAcceptor(
      ch: ServerChannelImpl,
      service: SocketPipelineBuilder
  )(key: SelectionKey): Selectable = {
    val acceptor = new SocketAcceptor(key, ch, service)
    try key.interestOps(SelectionKey.OP_ACCEPT)
    catch {
      case ex: CancelledKeyException => acceptor.close(Some(ex))
    }
    acceptor
  }

  private[this] def handleClientChannel(
      clientChannel: NIO1ClientChannel,
      service: SocketPipelineBuilder
  ): Unit =
    try {
      clientChannel.configureBlocking(false)
      clientChannel.configureOptions(channelOptions)

      val address = clientChannel.getRemoteAddress
      val loop = workerPool.nextLoop()
      val conn = NIO1Connection(clientChannel)

      // From within the selector loop, constructs a pipeline or
      // just closes the socket if the pipeline builder rejects it.
      def fromKey(key: SelectionKey): Selectable = {
        val head = new NIO1HeadStage(clientChannel, loop, key)
        service(conn).onComplete {
          case Success(tail) =>
            tail.base(head)
            head.inboundCommand(Command.Connected)
            logger.debug(s"Accepted connection from $address")

          case Failure(ex) =>
            // Service rejection isn't a network failure, so we close with None
            head.close(None)
            logger.info(ex)(s"Rejected connection from $address")
        }(loop)

        head
      }

      loop.initChannel(clientChannel, fromKey)
    } catch {
      case NonFatal(t) =>
        logger.error(t)("Error handling client channel. Closing.")
        try clientChannel.close()
        catch {
          case NonFatal(t2) =>
            logger.error(t2)("Error closing client channel after error")
        }
    }
}
