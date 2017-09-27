package org.http4s.blaze.channel

import java.net.{InetSocketAddress, Socket}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

import org.http4s.blaze.channel.nio1.NIO1SocketServerGroup
import org.http4s.blaze.channel.nio2.NIO2SocketServerGroup
import org.http4s.blaze.pipeline.{Command, TailStage}
import org.http4s.blaze.util.Execution
import org.specs2.mutable.Specification

import scala.concurrent.{Await, Awaitable, Future, Promise}
import scala.concurrent.duration._


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

      class ZeroWritingStage(batch: Boolean) extends TailStage[ByteBuffer] {
        private[this] val writeResult = Promise[Unit]

        def name = this.getClass.getSimpleName

        def completeF: Future[Unit] = writeResult.future

        override protected def stageStartup(): Unit = {
          val f = if (batch) channelWrite(Seq.empty) else channelWrite(ByteBuffer.allocate(0))
          writeResult.tryCompleteWith(f)
          f.onComplete(_ => sendOutboundCommand(Command.Disconnect))(Execution.directec)
        }
      }

      def writeBuffer(batch: Boolean): Unit = {
        val stage = new ZeroWritingStage(batch)
        val (group,channel) = new BasicServer(_ => stage, isNIO2).prepare(new InetSocketAddress(0))
        val socket = new Socket()
        socket.connect(channel.socketAddress)

        Await.result(stage.completeF, 2.seconds)
        socket.close()
        channel.close()
        group.closeGroup()
      }

      "Write an empty buffer" in {
        writeBuffer(false)
        ok // if we made it this far, it worked.
      }

      "Write an empty collection of buffers" in {
        writeBuffer(true)
        ok // if we made it this far, it worked.
      }
    }
  }
}
