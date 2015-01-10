package org.http4s.blaze.examples

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup

import org.http4s.blaze.channel._
import org.http4s.blaze.channel.nio2.NIO2SocketServerChannelFactory
import org.http4s.blaze.pipeline.TrunkBuilder
import org.http4s.blaze.pipeline.stages.SSLStage

class ClientAuthSSLHttpServer(port: Int) {

  private val sslContext = ExampleKeystore.clientAuthSslContext()

  private val f: BufferPipelineBuilder = { _ =>
    val eng = sslContext.createSSLEngine()
    eng.setUseClientMode(false)
    eng.setNeedClientAuth(true)

    TrunkBuilder(new SSLStage(eng, 100*1024)).cap(ExampleService.http1Stage(None, 10*1024))
  }

  val group = AsynchronousChannelGroup.withFixedThreadPool(10, java.util.concurrent.Executors.defaultThreadFactory())

  private val factory = new NIO2SocketServerChannelFactory(f)

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object ClientAuthSSLHttpServer {
  def main(args: Array[String]): Unit = new ClientAuthSSLHttpServer(4430).run()
}
