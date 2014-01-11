package org.http4s.examples.blaze

/**
 * @author Bryce Anderson
 *         Created on 1/10/14
 */


import blaze.channel._
import java.nio.channels.AsynchronousChannelGroup
import java.net.InetSocketAddress
import org.http4s.blaze.Http4sStage
import org.http4s.examples.ExampleRoute
import org.http4s.util.middleware.URITranslation

/**
 * @author Bryce Anderson
 *         Created on 1/10/14
 */
class BlazeExample(port: Int) {

  val route = new ExampleRoute().apply()

  private val f: PipeFactory = _.cap(new Http4sStage(URITranslation.translateRoot("/http4s")(route)))

  val group = AsynchronousChannelGroup.withFixedThreadPool(50, java.util.concurrent.Executors.defaultThreadFactory())

  private val factory = new ServerChannelFactory(f)

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object BlazeExample {
  def main(args: Array[String]): Unit = new BlazeExample(8080).run()
}
