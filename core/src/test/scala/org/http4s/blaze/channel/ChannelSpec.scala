package org.http4s.blaze.channel


import java.net.InetSocketAddress
import org.specs2.mutable.Specification
import org.http4s.blaze.channel.nio2.NIO2SocketServerChannelFactory
import org.log4s.getLogger
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger


class ChannelSpec extends Specification {

  class BasicServer(f: BufferPipelineBuilder) {
    private[this] val logger = getLogger

    def prepare(address: InetSocketAddress): ServerChannel = {
      val factory = new NIO2SocketServerChannelFactory(f)
      factory.bind(address)
    }

    def run(port: Int) {
      val address = new InetSocketAddress(port)
      val channel = prepare(address)

      logger.info(s"Starting server on address $address at time ${new Date}")
      val t = channel.run()
    }
  }


  "Channels" should {

    "Bind the port and then be closed" in {
      val channel = new BasicServer(_ => new EchoStage).prepare(new InetSocketAddress(0))
      channel.close()
      true should_== true
    }

    "Execute shutdown hooks" in {
      val i = new AtomicInteger(0)
      val channel = new BasicServer(_ => new EchoStage).prepare(new InetSocketAddress(0))
      channel.addShutdownHook{ () => i.incrementAndGet() }
      val t = channel.runAsync()
      channel.close()
      t.join(100)

      i.get should_== 1
    }

    "Execute shutdown hooks when one throws an exception" in {
      val i = new AtomicInteger(0)
      val channel = new BasicServer(_ => new EchoStage).prepare(new InetSocketAddress(0))
      channel.addShutdownHook{ () => i.incrementAndGet() }
      channel.addShutdownHook{ () => sys.error("Foo") }
      channel.addShutdownHook{ () => i.incrementAndGet() }
      val t = channel.runAsync()
      try channel.close()
      catch { case t: RuntimeException => i.incrementAndGet() }
      t.join(100)

      i.get should_== 3
    }
  }

}
