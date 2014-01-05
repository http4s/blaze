package channel

import org.scalatest.{Matchers, WordSpec}
import pipeline.TailStage
import java.nio.ByteBuffer
import scala.annotation.tailrec

import scala.concurrent.ExecutionContext.Implicits.global
import java.net.InetSocketAddress
import scala.util.{Failure, Success}

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */
class ChannelSpec extends WordSpec with Matchers {

  class EchoStage extends TailStage[ByteBuffer] {
    def name: String = "EchoStage"

    val msg = "Hello, ".getBytes

    final override def startup(): Unit = {
      channelRead().onComplete{
        case Success(buff) =>
          val b = ByteBuffer.allocate(buff.remaining() + msg.length)
          b.put(msg).put(buff).flip()

        // Write it, wait for conformation, and start again
          channelWrite(b).onSuccess{ case _ => startup() }

        case Failure(t) =>   logger.error("Channel read failed: " + t)
      }
    }
  }

  val f: PipeFactory = _.addLast(new EchoStage).result
  val address = new InetSocketAddress(8080)

  "Channels" should {
    "Be created" in {

      val factory = new ServerChannelFactory(f)
      val channel = factory.bind(address)

      println("Starting server")
      channel.run()
    }
  }

}
