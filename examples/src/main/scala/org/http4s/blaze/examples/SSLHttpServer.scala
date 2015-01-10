package org.http4s.blaze.examples

import org.http4s.blaze.channel._
import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup
import org.http4s.blaze.channel.nio1.NIO1SocketServerChannelFactory
import org.http4s.blaze.pipeline.stages.SSLStage

import org.http4s.blaze.channel.nio2.NIO2SocketServerChannelFactory
import org.http4s.blaze.pipeline.TrunkBuilder


class SSLHttpServer(port: Int) {

  private val sslContext = ExampleKeystore.sslContext()

  private val f: BufferPipelineBuilder = { _ =>
    val eng = sslContext.createSSLEngine()
    eng.setUseClientMode(false)
    TrunkBuilder(new SSLStage(eng, 100*1024)).cap(ExampleService.http1Stage(None, 10*1024))
  }

  private val factory = new NIO1SocketServerChannelFactory(f, workerThreads = 6)

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object SSLHttpServer {
  def main(args: Array[String]): Unit = new SSLHttpServer(4430).run()
}
