package org.http4s.blaze.channel.nio1

import java.nio.channels._
import scala.annotation.tailrec
import java.net.SocketAddress
import org.http4s.blaze.channel._
import scala.util.control.NonFatal
import org.log4s.getLogger

abstract class NIO1ServerChannelFactory[Channel <: NetworkChannel](pool: SelectorLoopPool)
                extends ServerChannelFactory[Channel] {

  def this(fixedPoolSize: Int, bufferSize: Int = 8*1024) = this(new FixedSelectorPool(fixedPoolSize, bufferSize))

  protected def doBind(address: SocketAddress): Channel

  protected def completeConnection(ch: Channel, loop: SelectorLoop): Boolean

  protected def makeSelector: Selector = Selector.open()

  protected def createServerChannel(channel: Channel): ServerChannel =
    new NIO1ServerChannel(channel, pool)

  override def bind(localAddress: SocketAddress = null): ServerChannel = {
    val c = doBind(localAddress)
    createServerChannel(c)
  }

  /** This class can be extended to change the way selector loops are provided */
  protected class NIO1ServerChannel(val channel: Channel, pool: SelectorLoopPool) extends ServerChannel {

    type C = Channel

    @volatile private var closed = false

    override def close(): Unit = {
      closed = true
      pool.shutdown()
      channel.close()
    }

    // The accept thread just accepts connections and pawns them off on the SelectorLoop pool
    final def run(): Unit = {
      while (channel.isOpen && !closed) {
        try {
          val p = pool.nextLoop()
          completeConnection(channel, p)
        } catch {
          case NonFatal(t) => logger.error(t)("Error accepting connection")
        }
      }
    }

  }
}
