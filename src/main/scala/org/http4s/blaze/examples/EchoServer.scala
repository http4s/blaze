package org.http4s.blaze
package examples

import org.http4s.blaze.pipeline.TailStage
import java.nio.ByteBuffer
import scala.util.{Failure, Success}
import org.http4s.blaze.channel.{BufferPipeline, ServerChannel}
import java.net.InetSocketAddress
import com.typesafe.scalalogging.slf4j.Logging
import java.util.Date

import scala.concurrent.ExecutionContext.Implicits.global
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.channel.nio2.NIO2ServerChannelFactory

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */



class EchoServer extends Logging {

  def prepare(address: InetSocketAddress): ServerChannel = {
    val f: BufferPipeline = () => new EchoStage

    val factory = new NIO2ServerChannelFactory(f)
    factory.bind(address)
  }
  
  def run(port: Int) {
    val address = new InetSocketAddress(port)
    val channel = prepare(address)

    logger.info(s"Starting server on address $address at time ${new Date}")
    channel.run()
  }

  private class EchoStage extends TailStage[ByteBuffer] {
    def name: String = "EchoStage"

    val msg = "echo: ".getBytes

    final override def stageStartup(): Unit = {
      channelRead().onComplete{
        case Success(buff) =>
          val b = ByteBuffer.allocate(buff.remaining() + msg.length)
          b.put(msg).put(buff).flip()

          // Write it, wait for conformation, and start again
          channelWrite(b).onSuccess{ case _ => stageStartup() }

        case Failure(EOF) => logger.trace("Channel closed.")
        case Failure(t) => logger.error("Channel read failed: " + t)
      }
    }
  }
}

object EchoServer {
  def main(args: Array[String]): Unit = new EchoServer().run(8080)
}
