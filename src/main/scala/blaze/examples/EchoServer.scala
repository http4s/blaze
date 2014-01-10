package blaze
package examples

import pipeline.TailStage
import java.nio.ByteBuffer
import scala.util.{Failure, Success}
import channel.{PipeFactory, ServerChannelFactory}
import java.net.InetSocketAddress
import com.typesafe.scalalogging.slf4j.Logging
import java.util.Date

import scala.concurrent.ExecutionContext.Implicits.global
import blaze.pipeline.Command.EOF

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */



class EchoServer extends Logging {

  def prepare(address: InetSocketAddress): ServerChannelFactory#ServerChannel = {
    val f: PipeFactory = _.cap(new EchoStage)

    val factory = new ServerChannelFactory(f)
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
