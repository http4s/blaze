package org.http4s.blaze.channel

import java.net.{InetSocketAddress, Socket}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import org.http4s.blaze.channel.nio1.NIO1SocketServerGroup
import org.http4s.blaze.channel.nio2.NIO2SocketServerGroup
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}
import org.http4s.blaze.util.Execution
import org.specs2.mutable.Specification
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._


class NIO1ChannelSpec extends BaseChannelSpec {
  override protected def bind(f: SocketPipelineBuilder): ServerPair = {
    val factory = NIO1SocketServerGroup.fixedGroup(workerThreads = 2)

    val channel = factory.bind(new InetSocketAddress(0), f).get // will throw if failed to bind
    ServerPair(factory, channel)
  }
}

class NIO2ChannelSpec extends BaseChannelSpec {
  override protected def bind(f: SocketPipelineBuilder): ServerPair = {
    val factory = NIO2SocketServerGroup.fixedGroup(workerThreads = 2)

    val channel = factory.bind(new InetSocketAddress(0), f).get // will throw if failed to bind
    ServerPair(factory, channel)
  }

  "NIO2 Channel" should {
    "throw an exception when trying to shutdown the system default group" in {
      NIO2SocketServerGroup().closeGroup() must throwA[IllegalStateException]
    }
  }
}

abstract class BaseChannelSpec extends Specification {

  protected case class ServerPair(group: ServerChannelGroup, channel: ServerChannel)

  protected def bind(f: SocketPipelineBuilder): ServerPair

  private def bindEcho(): ServerPair = {
    bind{ _ => Future.successful(LeafBuilder(new EchoStage)) }
  }

  "Bind the port and then be closed" in {
    val ServerPair(group,channel) = bindEcho()
    Thread.sleep(1000L)
    channel.close()
    group.closeGroup()
    channel.join()
    ok
  }

  "Execute shutdown hooks" in {
    val i = new AtomicInteger(0)
    val ServerPair(group,channel) = bindEcho()
    channel.addShutdownHook{ () => i.incrementAndGet(); () } must_== true
    channel.close()
    group.closeGroup()
    channel.join()
    i.get should_== 1
  }

  "Execute shutdown hooks when one throws an exception" in {
    val i = new AtomicInteger(0)
    val ServerPair(group,channel) = bindEcho()
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
    val ServerPair(group,channel) = bindEcho()
    channel.addShutdownHook{ () => i.incrementAndGet(); () } must_== true
    group.closeGroup()

    channel.join()

    i.get should_== 1
  }

  "Not register a hook on a shutdown ServerChannel" in {
    val ServerPair(group,channel) = bindEcho()
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
      f.onComplete(_ => closePipeline(None))(Execution.directec)
    }
  }

  def writeBuffer(batch: Boolean): Unit = {
    val stage = new ZeroWritingStage(batch)
    val ServerPair(group,channel) = bind { _ => Future.successful(LeafBuilder(stage)) }
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
