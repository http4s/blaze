package blaze.examples

import blaze.channel._
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import blaze.pipeline.stages.SerializingStage
import java.nio.ByteBuffer

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */
class HttpServer(port: Int) {

  private val f: PipeFactory = _.cap(new ExampleHttpStage(10*1024))

  val group = AsynchronousChannelGroup.withFixedThreadPool(10, java.util.concurrent.Executors.defaultThreadFactory())

  private val factory = new ServerChannelFactory(f)

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object HttpServer {
  def main(args: Array[String]): Unit = new HttpServer(8080).run()
}
