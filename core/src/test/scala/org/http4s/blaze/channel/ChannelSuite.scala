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

package org.http4s.blaze.channel

import java.net.{InetSocketAddress, Socket}
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

import munit.FunSuite
import org.http4s.blaze.channel.nio1.NIO1SocketServerGroup
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}
import org.http4s.blaze.util.Execution

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._

class NIO1ChannelSuite extends BaseChannelSuite {
  override protected def bind(f: SocketPipelineBuilder): ServerPair = {
    val factory = NIO1SocketServerGroup.fixed(workerThreads = 2)

    val channel =
      factory.bind(new InetSocketAddress("localhost", 0), f).get // will throw if failed to bind
    ServerPair(factory, channel)
  }
}

abstract class BaseChannelSuite extends FunSuite {
  protected case class ServerPair(group: ServerChannelGroup, channel: ServerChannel)

  protected def bind(f: SocketPipelineBuilder): ServerPair

  private def bindEcho(): ServerPair =
    bind(_ => Future.successful(LeafBuilder(new EchoStage)))

  test("Bind the port and then be closed") {
    val ServerPair(group, channel) = bindEcho()
    Thread.sleep(1000L)
    channel.close()
    group.closeGroup()
    channel.join()
  }

  test("Execute shutdown hooks") {
    val i = new AtomicInteger(0)
    val ServerPair(group, channel) = bindEcho()
    assert(channel.addShutdownHook { () => i.incrementAndGet(); () })
    channel.close()
    group.closeGroup()
    channel.join()

    assertEquals(i.get, 1)
  }

  test("Execute shutdown hooks when one throws an exception") {
    val i = new AtomicInteger(0)
    val ServerPair(group, channel) = bindEcho()
    assert(channel.addShutdownHook { () => i.incrementAndGet(); () })
    assert(channel.addShutdownHook(() => sys.error("Foo")))
    assert(channel.addShutdownHook { () => i.incrementAndGet(); () })
    channel.close()

    group.closeGroup()
    channel.join()

    assertEquals(i.get, 2)
  }

  test("Execute shutdown hooks when the ServerChannelGroup is shutdown") {
    val i = new AtomicInteger(0)
    val ServerPair(group, channel) = bindEcho()
    assert(channel.addShutdownHook { () => i.incrementAndGet(); () })
    group.closeGroup()

    channel.join()

    assertEquals(i.get, 1)
  }

  test("Not register a hook on a shutdown ServerChannel") {
    val ServerPair(group, channel) = bindEcho()
    channel.close()
    group.closeGroup()

    assertEquals(channel.addShutdownHook(() => sys.error("Blam!")), false)
  }

  class ZeroWritingStage(batch: Boolean) extends TailStage[ByteBuffer] {
    private[this] val writeResult = Promise[Unit]()

    def name = this.getClass.getSimpleName

    def completeF: Future[Unit] = writeResult.future

    override protected def stageStartup(): Unit = {
      val f = if (batch) channelWrite(Seq.empty) else channelWrite(ByteBuffer.allocate(0))
      writeResult.completeWith(f)
      f.onComplete(_ => closePipeline(None))(Execution.directec)
    }
  }

  def writeBuffer(batch: Boolean): Unit = {
    val stage = new ZeroWritingStage(batch)
    val ServerPair(group, channel) = bind(_ => Future.successful(LeafBuilder(stage)))
    val socket = new Socket()
    socket.connect(channel.socketAddress)

    Await.result(stage.completeF, 2.seconds)
    socket.close()
    channel.close()
    group.closeGroup()
  }

  test("Write an empty buffer") {
    writeBuffer(false)
  }

  test("Write an empty collection of buffers") {
    writeBuffer(true)
  }
}
