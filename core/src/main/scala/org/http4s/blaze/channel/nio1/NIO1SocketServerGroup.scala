package org.http4s.blaze
package channel
package nio1

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels._
import java.net.SocketAddress
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

import org.http4s.blaze.channel.ChannelHead._
import org.http4s.blaze.channel.nio1.NIO1HeadStage.{WriteError, Incomplete, Complete, WriteResult}
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.util.BufferTools
import org.log4s._

import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

object NIO1SocketServerGroup {

  /** Default size of buffer to use in a [[SelectorLoop]] */
  val defaultBufferSize: Int = 32*1024

  def apply(pool: SelectorLoopPool): NIO1SocketServerGroup =
    new NIO1SocketServerGroup(pool)

  /** Create a new [[NIO1SocketServerGroup]] with a fresh [[FixedSelectorPool]] */
  def fixedGroup(workerThreads: Int = defaultPoolSize, bufferSize: Int = defaultBufferSize): NIO1SocketServerGroup = {
    val pool = new FixedSelectorPool(workerThreads, bufferSize)
    new NIO1SocketServerGroup(pool)
  }
}

/** A thread resource group for NIO1 network operations
  *
  * @param pool [[SelectorLoopPool]] that will belong to this group. The group assumes responsibility
  *            for shutting it down. Shutting down the pool after giving it to this group will result
  *            in undefined behavior.
  */
class NIO1SocketServerGroup(pool: SelectorLoopPool) extends ServerChannelGroup {
  private[this] val logger = getLogger

  @volatile private var isClosed = false

  private val s = Selector.open()

  private val t = new AcceptThread()
  t.start()

  override def closeGroup() {
    logger.info("Closing NIO1SocketServerGroup")
    isClosed = true
    s.wakeup()
  }


  /** Create a [[ServerChannel]] that will serve the services on the requisite sockets */
  override def bind(address: SocketAddress, service: BufferPipelineBuilder): Try[ServerChannel] = {
    Try{
      val ch = ServerSocketChannel.open().bind(address)
      val serverChannel = new NIO1ServerChannel(ch, service)
      t.listenOnChannel(serverChannel)

      logger.info("Service bound to address " + ch.getLocalAddress)
      serverChannel
    }
  }


  private class AcceptThread extends Thread("NIO1SocketServerChannelFactory") {
    setDaemon(true)

  private val queue = new AtomicReference[List[NIO1ServerChannel]](Nil)

    /** Add a channel to the selector loop */
    def listenOnChannel(channel: NIO1ServerChannel) {
      def go(): Unit = queue.get() match {
        case null                                    => channel.close() // Queue is closed.
        case q if queue.compareAndSet(q, channel::q) => s.wakeup()      // Successful set. Wake the loop.
        case _                                       => go()            // Lost race. Try again.
      }

      go()
    }

    override def run(): Unit = {
      while (!isClosed) {
        s.select()      // wait for connections to come in

        // Add any new connections
        val q = queue.getAndSet(Nil)

        q.foreach { ch =>
          try {
            ch.channel.configureBlocking(false)
            ch.channel.register(s, SelectionKey.OP_ACCEPT, ch)

          } catch {
            case NonFatal(t) =>
              logger.error(t)("Error during channel registration: " + ch.channel.getLocalAddress())
              try ch.close()
              catch { case NonFatal(t) => logger.debug(t)("Failure during channel close") }
          }
        }

        val it = s.selectedKeys().iterator()

        while (it.hasNext()) {
          val key = it.next()
          it.remove()

          val channel = key.attachment().asInstanceOf[NIO1ServerChannel]
          val serverChannel = channel.channel
          val service = channel.service
          val loop = pool.nextLoop()

          try {
            val clientChannel = serverChannel.accept()

            if (clientChannel != null) {                       // This should never be `null`
            val address = clientChannel.getRemoteAddress()

              // check to see if we want to keep this connection
              if (acceptConnection(address)) {
                clientChannel.setOption[java.lang.Boolean](java.net.StandardSocketOptions.TCP_NODELAY, false)
                loop.initChannel(service, clientChannel, key => new SocketChannelHead(clientChannel, loop, key))
              }
              else clientChannel.close()
            }
          }
          catch {
            case NonFatal(e) =>
              val localAddress = serverChannel.getLocalAddress()
              logger.error(e)(s"Error accepting connection on address $localAddress")

              // If the server channel cannot go on, disconnect it.
              if (!serverChannel.isOpen()) {
                logger.error(s"Channel bound to address $localAddress has been unexpectedly closed.")
                key.cancel()
                channel.close()
              }

            case t: Throwable =>
              logger.error(t)("Fatal error in connection accept loop. Closing Group.")
              closeGroup() // will cause the closing of the attached channels
          }
        }
      }

      // We have been closed. Close all the attached channels as well
      val it = s.keys().iterator()
      while (it.hasNext()) {
        val key = it.next()
        val ch = key.attachment().asInstanceOf[NIO1ServerChannel]
        key.cancel()
        ch.close()

      }

      // Close down the selector loops
      pool.shutdown()

      // clear out the queue
      queue.getAndSet(null).foreach { ch =>
        try ch.close()
        catch { case NonFatal(t) => logger.debug(t)("Failure during channel close") }
      }

      // Finally close the selector
      try s.close()
      catch { case NonFatal(t) => logger.debug(t)("Failure during selector close") }
    }
  } // thread


  private class NIO1ServerChannel(val channel: ServerSocketChannel, val service:BufferPipelineBuilder)
    extends ServerChannel
  {

    override protected def closeChannel() {
      logger.info(s"Closing NIO1 channel ${channel.getLocalAddress()} at ${new Date}")
      try channel.close()
      catch { case NonFatal(t) => logger.debug(t)("Failure during channel close") }
    }
  }

  // Implementation of the channel head that can deal explicitly with a SocketChannel
  private class SocketChannelHead(ch: SocketChannel,
                                  loop: SelectorLoop,
                                  key: SelectionKey) extends NIO1HeadStage(ch, loop, key)
  {
    override protected def performRead(scratch: ByteBuffer): Try[ByteBuffer] = {
      try {
        scratch.clear()
        val bytes = ch.read(scratch)
        if (bytes >= 0) {
          scratch.flip()

          val b = BufferTools.allocate(scratch.remaining())
          b.put(scratch)
          b.flip()
          Success(b)
        }
        else Failure(EOF)

      } catch {
        case e: ClosedChannelException => Failure(EOF)
        case e: IOException if brokePipeMessages.contains(e.getMessage) => Failure(EOF)
        case e: IOException => Failure(e)
      }
    }

    override protected def performWrite(scratch: ByteBuffer, buffers: Array[ByteBuffer]): WriteResult = {
      try {
        ch.write(buffers)
        if (util.BufferTools.checkEmpty(buffers)) Complete
        else Incomplete
      }
      catch {
        case e: ClosedChannelException => WriteError(EOF)
        case e: IOException if brokePipeMessages.contains(e.getMessage) => WriteError(EOF)
        case e: IOException =>
          logger.warn(e)("Error writing to channel")
          WriteError(e)
      }
    }
  }
}
