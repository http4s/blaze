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

package org.http4s.blaze.pipeline.stages.monitors

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

import org.http4s.blaze.channel.SocketPipelineBuilder
import org.http4s.blaze.pipeline.MidStage
import org.http4s.blaze.util.Execution
import org.http4s.blaze.util.Execution.directec

import scala.concurrent.Future

/** Facilitates injecting some monitoring tools into the pipeline */
abstract class ConnectionMonitor {
  def wrapBuilder(factory: SocketPipelineBuilder): SocketPipelineBuilder =
    factory(_).map(_.prepend(new ServerStatusStage))(Execution.trampoline)

  protected def connectionAccepted(): Unit
  protected def connectionClosed(): Unit

  protected def bytesInbound(n: Long): Unit
  protected def bytesOutBound(n: Long): Unit

  private[this] class ServerStatusStage extends MidStage[ByteBuffer, ByteBuffer] {
    val name = "ServerStatusStage"

    private val cleaned = new AtomicBoolean(false)

    private def clearCount() =
      if (!cleaned.getAndSet(true))
        connectionClosed()

    override def stageStartup(): Unit = {
      connectionAccepted()
      super.stageStartup()
    }

    override def stageShutdown(): Unit = {
      clearCount()
      cleaned.set(true)
      super.stageShutdown()
    }

    def writeRequest(data: ByteBuffer): Future[Unit] = {
      bytesOutBound(data.remaining.toLong)
      channelWrite(data)
    }

    override def writeRequest(data: collection.Seq[ByteBuffer]): Future[Unit] = {
      bytesOutBound(data.foldLeft(0)((i, b) => i + b.remaining()).toLong)
      channelWrite(data)
    }

    def readRequest(size: Int): Future[ByteBuffer] =
      channelRead(size).map { b => bytesInbound(b.remaining.toLong); b }(directec)
  }
}
