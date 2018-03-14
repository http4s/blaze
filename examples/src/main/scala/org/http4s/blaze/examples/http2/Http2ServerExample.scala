package org.http4s.blaze.examples.http2

import java.net.InetSocketAddress

import org.http4s.blaze.channel
import org.http4s.blaze.channel._
import org.http4s.blaze.channel.nio1.NIO1SocketServerGroup
import org.http4s.blaze.examples.{ExampleKeystore, ExampleService}
import org.http4s.blaze.http.HttpServerStageConfig
import org.http4s.blaze.http.http2.server.ServerSelector
import org.http4s.blaze.pipeline.TrunkBuilder
import org.http4s.blaze.pipeline.stages.SSLStage

import scala.concurrent.Future

/** Basic HTTP/2 server example
  *
  * The server is capable of serving traffic over both HTTP/1.x and HTTP/2
  * using TLS.
  *
  * @note the Jetty ALPN boot library must have been loaded in order for
  *       ALPN negotiation to happen. See the Jetty docs at
  *       https://www.eclipse.org/jetty/documentation/9.3.x/alpn-chapter.html
  *       for more information.
  */
class Http2ServerExample(port: Int) {
  private val sslContext = ExampleKeystore.sslContext()

  private val f: SocketPipelineBuilder = { _ =>
    val eng = sslContext.createSSLEngine()
    eng.setUseClientMode(false)
    Future.successful(
      TrunkBuilder(new SSLStage(eng))
        .cap(ServerSelector(eng, ExampleService.service(None), HttpServerStageConfig())))
  }

  private val factory =
    NIO1SocketServerGroup.fixedGroup(workerThreads = channel.DefaultPoolSize)

  def run(): ServerChannel =
    factory
      .bind(new InetSocketAddress(port), f)
      .getOrElse(sys.error("Failed to start server."))
}

object Http2ServerExample {
  def main(args: Array[String]): Unit = {
    val port = 8443
    println(s"Starting HTTP/2 compatible server on port $port")
    new Http2ServerExample(port).run().join()
  }
}
