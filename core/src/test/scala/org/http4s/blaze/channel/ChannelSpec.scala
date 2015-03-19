package org.http4s.blaze.channel


import java.net.InetSocketAddress
import java.util.Date
import java.util.concurrent.atomic.AtomicInteger

import org.http4s.blaze.channel.nio1.NIO1SocketServerChannelFactory
import org.specs2.mutable.Specification

import org.http4s.blaze.channel.nio2.NIO2SocketServerChannelFactory

import org.log4s.getLogger


class ChannelSpec extends Specification {

  class BasicServer(f: BufferPipelineBuilder, nio2: Boolean) {
    private[this] val logger = getLogger

    def prepare(address: InetSocketAddress): ServerChannel = {
      val factory = if (nio2) NIO2SocketServerChannelFactory(f)
                    else      NIO1SocketServerChannelFactory(f)
      factory.bind(address)
    }

    def run(port: Int) {
      val address = new InetSocketAddress(port)
      val channel = prepare(address)

      logger.info(s"Starting server on address $address at time ${new Date}")
      val t = channel.run()
    }
  }

  val CommonDelay = 1000

  "NIO1 Channels" should {

    val IsNIO2 = false

    "Bind the port and then be closed" in {
      val channel = new BasicServer(_ => new EchoStage, IsNIO2).prepare(new InetSocketAddress(0))
      val t = channel.runAsync
      Thread.sleep(CommonDelay)
      channel.close()
      t.join()
      true should_== true
    }

    "Execute shutdown hooks" in {
      val i = new AtomicInteger(0)
      val channel = new BasicServer(_ => new EchoStage, IsNIO2).prepare(new InetSocketAddress(0))
      channel.addShutdownHook{ () => i.incrementAndGet() }
      val t = channel.runAsync()
      channel.close()
      t.join(CommonDelay)

      i.get should_== 1
    }

    "Execute shutdown hooks when one throws an exception" in {
      val i = new AtomicInteger(0)
      val channel = new BasicServer(_ => new EchoStage, IsNIO2).prepare(new InetSocketAddress(0))
      channel.addShutdownHook{ () => i.incrementAndGet() }
      channel.addShutdownHook{ () => sys.error("Foo") }
      channel.addShutdownHook{ () => i.incrementAndGet() }
      val t = channel.runAsync()
      try channel.close()
      catch { case t: RuntimeException => i.incrementAndGet() }
      t.join(CommonDelay)

      i.get should_== 3
    }
  }


  "NIO2 Channels" should {

    val IsNIO2 = true

    "Bind the port and then be closed" in {
      val channel = new BasicServer(_ => new EchoStage, IsNIO2).prepare(new InetSocketAddress(0))
      val t = channel.runAsync
      Thread.sleep(CommonDelay)
      channel.close()
      t.join()
      true should_== true
    }

    "Execute shutdown hooks" in {
      val i = new AtomicInteger(0)
      val channel = new BasicServer(_ => new EchoStage, IsNIO2).prepare(new InetSocketAddress(0))
      channel.addShutdownHook{ () => i.incrementAndGet() }
      val t = channel.runAsync()
      channel.close()
      t.join(CommonDelay)

      i.get should_== 1
    }

    "Execute shutdown hooks when one throws an exception" in {
      val i = new AtomicInteger(0)
      val channel = new BasicServer(_ => new EchoStage, IsNIO2).prepare(new InetSocketAddress(0))
      channel.addShutdownHook{ () => i.incrementAndGet() }
      channel.addShutdownHook{ () => sys.error("Foo") }
      channel.addShutdownHook{ () => i.incrementAndGet() }
      val t = channel.runAsync()
      try channel.close()
      catch { case t: RuntimeException => i.incrementAndGet() }
      t.join(CommonDelay)

      i.get should_== 3
    }
  }
}
