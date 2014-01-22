package blaze.examples

import blaze.channel._

import java.net.InetSocketAddress
import blaze.channel.nio1.SocketServerChannelFactory

/**
 * @author Bryce Anderson
 *         Created on 1/21/14
 */
class NIO1HttpServer(port: Int) {

  private val f: PipeFactory = _.cap(new ExampleHttpStage(10*1024))

  private val factory = new SocketServerChannelFactory(f, workerThreads = 6)

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object NIO1HttpServer {
  def main(args: Array[String]): Unit = new NIO1HttpServer(8080).run()
}
