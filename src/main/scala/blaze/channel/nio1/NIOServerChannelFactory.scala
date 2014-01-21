package blaze.channel.nio1

import java.nio.channels._
import scala.annotation.tailrec
import java.net.SocketAddress
import com.typesafe.scalalogging.slf4j.Logging
import blaze.channel._

/**
 * @author Bryce Anderson
 *         Created on 1/19/14
 */
abstract class NIOServerChannelFactory(pipeFactory: PipeFactory, bufferSize: Int, workerThreads: Int) extends Logging {
  
  type Channel <: NetworkChannel // <: SelectableChannel with ByteChannel with NetworkChannel

  def doBind(address: SocketAddress): Channel

  def accept(ch: Channel): Option[ChannelOps]

  protected def makeSelector: Selector = Selector.open()

  def bind(localAddress: SocketAddress = null): ServerChannel = {
    if (pipeFactory == null) sys.error("Pipeline factory required")
    
    val pool = 0.until(workerThreads).map { _ => new SelectorLoop(makeSelector, bufferSize) }.toArray
    
    new ServerChannel(doBind(localAddress), pool)
  }
  
  

  class ServerChannel private[NIOServerChannelFactory](channel: Channel, pool: Array[SelectorLoop]) extends Runnable {

    private var nextPool: Int = 0
    @volatile private var closed = false

    def close(): Unit = {
      closed = true
      pool.foreach(_.close())
    }

    def runAsync(): Unit = {
      logger.trace("Starting server loop on separate thread")
      new Thread(this).start()
    }

    // The accept thread just accepts connections and pawns them off on the SelectorLoop pool
    @tailrec
    final def run(): Unit = {
      if (channel.isOpen) {
        accept(channel) match {
          case Some(ops) =>
            val p = nextPool % pool.length
            nextPool += 1

            pool(p).initChannel(pipeFactory, ops)

          case None => // NOOP
        }

        if (!closed) run()
      }

    }

  }

}
