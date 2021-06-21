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

import org.http4s.blaze.channel.nio1.NIO1SocketServerGroup
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}
import org.http4s.blaze.testkit.BlazeTestSuite
import org.http4s.blaze.util.Execution

import scala.concurrent.{Future, Promise}

class NIO1ChannelSuite extends BaseChannelSuite {
  override protected def bind(f: SocketPipelineBuilder): ServerPair = {
    val factory = NIO1SocketServerGroup.fixed(workerThreads = 2)

    val channel =
      factory.bind(new InetSocketAddress("localhost", 0), f).get // will throw if failed to bind
    ServerPair(factory, channel)
  }
}

abstract class BaseChannelSuite extends BlazeTestSuite {
  protected case class ServerPair(group: ServerChannelGroup, channel: ServerChannel)

  protected def bind(f: SocketPipelineBuilder): ServerPair

  private def bindEcho(): ServerPair =
    bind(_ => Future.successful(LeafBuilder(new EchoStage)))

  test("Bind the port and then be closed") {
    val ServerPair(group, channel) = bindEcho()

    val computation =
      for {
        _ <- Future(Thread.sleep(100))
        _ <- Future {
          channel.close()
          group.closeGroup()
          channel.join()
        }
      } yield ()

    assertFuture_(computation)
  }

  test("Execute shutdown hooks") {
    val i = new AtomicInteger(0)
    val ServerPair(group, channel) = bindEcho()

    for {
      _ <- assertFutureBoolean(Future(channel.addShutdownHook { () => i.incrementAndGet(); () }))
      _ <- Future {
        channel.close()
        group.closeGroup()
        channel.join()
      }
      _ <- assertFuture(Future(i.get), 1)
    } yield ()
  }

  test("Execute shutdown hooks when one throws an exception") {
    val i = new AtomicInteger(0)
    val ServerPair(group, channel) = bindEcho()

    for {
      _ <- assertFutureBoolean(Future(channel.addShutdownHook { () => i.incrementAndGet(); () }))
      _ <- assertFutureBoolean(Future(channel.addShutdownHook(() => sys.error("Foo"))))
      _ <- assertFutureBoolean(Future(channel.addShutdownHook { () => i.incrementAndGet(); () }))

      _ <- Future {
        channel.close()
        group.closeGroup()
        channel.join()
      }
      _ <- assertFuture(Future(i.get), 2)
    } yield ()
  }

  test("Execute shutdown hooks when the ServerChannelGroup is shutdown") {
    val i = new AtomicInteger(0)
    val ServerPair(group, channel) = bindEcho()

    for {
      _ <- assertFutureBoolean(Future(channel.addShutdownHook { () => i.incrementAndGet(); () }))
      _ <- Future {
        group.closeGroup()
        channel.join()
      }
      _ <- assertFuture(Future(i.get), 1)
    } yield ()
  }

  test("Not register a hook on a shutdown ServerChannel") {
    val ServerPair(group, channel) = bindEcho()

    for {
      _ <- Future {
        channel.close()
        group.closeGroup()
      }
      _ <- assertFuture(Future(channel.addShutdownHook(() => sys.error("Blam!"))), false)
    } yield ()
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

  private def writeBufferTest(testName: String, batch: Boolean): Unit = {
    val stage = new ZeroWritingStage(batch)
    val ServerPair(group, channel) = bind(_ => Future.successful(LeafBuilder(stage)))
    val socket = new Socket()

    test(testName) {
      for {
        _ <- Future(socket.connect(channel.socketAddress))
        _ <- assertFuture_(stage.completeF)
        _ <- Future {
          socket.close()
          channel.close()
          group.closeGroup()
        }
      } yield ()
    }
  }

  writeBufferTest("Write an empty buffer", batch = false)

  writeBufferTest("Write an empty collection of buffers", batch = true)
}
