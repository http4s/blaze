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

package org.http4s.blaze
package examples

import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}
import java.nio.ByteBuffer

import org.http4s.blaze.util.BufferTools

import scala.util.{Failure, Success}
import org.http4s.blaze.channel.{ServerChannel, SocketPipelineBuilder}
import java.net.InetSocketAddress
import java.util.Date

import scala.concurrent.ExecutionContext.Implicits.global
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.channel.nio1.NIO1SocketServerGroup
import org.log4s.getLogger

import scala.concurrent.Future

class EchoServer {
  private[this] val logger = getLogger

  def prepare(address: InetSocketAddress): ServerChannel = {
    val f: SocketPipelineBuilder = _ => Future.successful(LeafBuilder(new EchoStage))

    NIO1SocketServerGroup
      .fixed()
      .bind(address, f)
      .getOrElse(sys.error("Failed to start server."))
  }

  def run(port: Int): Unit = {
    val address = new InetSocketAddress(port)
    val channel = prepare(address)

    logger.info(s"Starting server on address $address at time ${new Date}")
    channel.join()
  }

  private class EchoStage extends TailStage[ByteBuffer] {
    def name: String = "EchoStage"

    val msg = "echo: ".getBytes

    final override def stageStartup(): Unit =
      channelRead().onComplete {
        case Success(buff) =>
          val b = BufferTools.allocate(buff.remaining() + msg.length)
          b.put(msg).put(buff).flip()

          // Write it, wait for conformation, and start again
          channelWrite(b).foreach(_ => stageStartup())

        case Failure(EOF) => this.logger.debug("Channel closed.")
        case Failure(t) => this.logger.error("Channel read failed: " + t)
      }
  }
}

object EchoServer {
  def main(args: Array[String]): Unit = new EchoServer().run(8080)
}
