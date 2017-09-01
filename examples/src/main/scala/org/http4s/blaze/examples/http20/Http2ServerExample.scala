package org.http4s.blaze.examples.http20

import java.net.InetSocketAddress

import org.http4s.blaze.channel
import org.http4s.blaze.channel._
import org.http4s.blaze.channel.nio1.NIO1SocketServerGroup
import org.http4s.blaze.examples.{ExampleKeystore, ExampleService}
import org.http4s.blaze.http.HttpServerStageConfig
import org.http4s.blaze.http.http20.Http2Selector
import org.http4s.blaze.pipeline.TrunkBuilder
import org.http4s.blaze.pipeline.stages.SSLStage

class Http2ServerExample(port: Int) {
  private val sslContext = ExampleKeystore.sslContext()

  private val f: BufferPipelineBuilder = { _ =>
    val eng = sslContext.createSSLEngine()
    eng.setUseClientMode(false)
    TrunkBuilder(new SSLStage(eng)).cap(Http2Selector(eng, ExampleService.service(None), HttpServerStageConfig()))
  }

  private val factory = NIO1SocketServerGroup.fixedGroup(workerThreads = channel.defaultPoolSize)

  def run(): ServerChannel = factory.bind(new InetSocketAddress(port), f).getOrElse(sys.error("Failed to start server."))
}

object Http2ServerExample {
  def main(args: Array[String]): Unit = {
    println("Hello world!")
    new Http2ServerExample(8443).run().join()
  }
}
