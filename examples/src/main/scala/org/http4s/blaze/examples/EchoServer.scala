package org.http4s.blaze
package examples

import org.http4s.blaze.pipeline.TailStage
import java.nio.ByteBuffer
import org.http4s.blaze.util.BufferTools

import scala.util.{Failure, Success}
import org.http4s.blaze.channel.{BufferPipelineBuilder, ServerChannel}
import java.net.InetSocketAddress
import java.util.Date

import scala.concurrent.ExecutionContext.Implicits.global
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.channel.nio2.NIO2SocketServerGroup
import org.log4s.getLogger

class EchoServer {
  private[this] val logger = getLogger

  def prepare(address: InetSocketAddress): ServerChannel = {
    val f: BufferPipelineBuilder = _ => new EchoStage

    NIO2SocketServerGroup()
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
