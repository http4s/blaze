package org.http4s.blaze.channel

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

import org.http4s.blaze.channel.nio1.NIO1SocketServerGroup
import org.http4s.blaze.channel.nio2.NIO2SocketServerGroup

import org.specs2.mutable.Specification


class ChannelSpec extends Specification {

  class BasicServer(f: BufferPipelineBuilder, nio2: Boolean) {

    def prepare(address: InetSocketAddress): (ServerChannelGroup, ServerChannel) = {
      val factory = if (nio2) NIO2SocketServerGroup.fixedGroup(workerThreads = 2)
                    else      NIO1SocketServerGroup.fixedGroup(workerThreads = 2)

      (factory, factory.bind(address, f).getOrElse(sys.error("Failed to initialize socket at address " + address)))
    }
  }

  val CommonDelay = 1000

  testNIO(false)

  testNIO(true)

  "NIO2 Channel" should {
    "throw an exception when trying to shutdown the system default group" in {
      NIO2SocketServerGroup().closeGroup() must throwA[IllegalStateException]
    }
  }

  def testNIO(isNIO2: Boolean) = {
    val title = (if (isNIO2) "NIO2" else "NIO1") + " Channels"

    title should {
      "Bind the port and then be closed" in {
        val (group,channel) = new BasicServer(_ => new EchoStage, isNIO2).prepare(new InetSocketAddress(0))
        Thread.sleep(CommonDelay.toLong)
        channel.close()
        group.closeGroup()
        channel.join()
        true should_== true
      }

      "Execute shutdown hooks" in {
        val i = new AtomicInteger(0)
        val (group,channel) = new BasicServer(_ => new EchoStage, isNIO2).prepare(new InetSocketAddress(0))
        channel.addShutdownHook{ () => i.incrementAndGet(); () } must_== true
        channel.close()
        group.closeGroup()
        channel.join()
        i.get should_== 1
      }

      "Execute shutdown hooks when one throws an exception" in {
        val i = new AtomicInteger(0)
        val (group,channel) = new BasicServer(_ => new EchoStage, isNIO2).prepare(new InetSocketAddress(0))
        channel.addShutdownHook{ () => i.incrementAndGet(); () } must_== true
        channel.addShutdownHook{ () => sys.error("Foo") }    must_== true
        channel.addShutdownHook{ () => i.incrementAndGet(); () } must_== true
        channel.close()

        group.closeGroup()
        channel.join()

        i.get should_== 2
      }

      "Execute shutdown hooks when the ServerChannelGroup is shutdown" in {
        val i = new AtomicInteger(0)
        val (group,channel) = new BasicServer(_ => new EchoStage, isNIO2).prepare(new InetSocketAddress(0))
        channel.addShutdownHook{ () => i.incrementAndGet(); () } must_== true
        group.closeGroup()

        channel.join()

        i.get should_== 1
      }

      "Not register a hook on a shutdown ServerChannel" in {
        val (group,channel) = new BasicServer(_ => new EchoStage, isNIO2).prepare(new InetSocketAddress(0))
        channel.close()
        group.closeGroup()
        channel.addShutdownHook { () => sys.error("Blam!") } must_== false
      }
    }
  }
}
