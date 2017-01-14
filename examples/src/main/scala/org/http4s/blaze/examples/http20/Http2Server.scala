package org.http4s.blaze.examples.http20

import java.net.InetSocketAddress

import org.http4s.blaze.channel._
import org.http4s.blaze.channel.nio1.NIO1SocketServerGroup
import org.http4s.blaze.examples.{Consts, ExampleKeystore, ExampleService}
import org.http4s.blaze.http.HttpServerConfig
import org.http4s.blaze.http.http20.Http2Selector
import org.http4s.blaze.pipeline.TrunkBuilder
import org.http4s.blaze.pipeline.stages.SSLStage

class Http2Server(port: Int) {
  private val sslContext = ExampleKeystore.sslContext()
  val ec = scala.concurrent.ExecutionContext.global

  private val f: BufferPipelineBuilder = { _ =>
    val eng = sslContext.createSSLEngine()
    eng.setUseClientMode(false)
    TrunkBuilder(new SSLStage(eng)).cap(Http2Selector(eng, ExampleService.service(None), HttpServerConfig()))
  }

  private val factory = NIO1SocketServerGroup.fixedGroup(workerThreads = Consts.poolSize)

  def run(): ServerChannel = factory.bind(new InetSocketAddress(port), f).getOrElse(sys.error("Failed to start server."))
}

object Http2Server {
  def main(args: Array[String]) {
    println("Hello world!")
    new Http2Server(8443).run().join()
  }
}
