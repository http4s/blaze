package org.http4s.blaze.examples.http20

import java.net.InetSocketAddress

import org.eclipse.jetty.alpn.ALPN

import org.http4s.blaze.channel._
import org.http4s.blaze.channel.nio1.NIO1SocketServerChannelFactory
import org.http4s.blaze.examples.{Consts, ExampleService, ExampleKeystore}
import org.http4s.blaze.http.http20.NodeMsg
import org.http4s.blaze.pipeline.TrunkBuilder
import org.http4s.blaze.pipeline.stages.SSLStage

class Http2Server(port: Int) {
  private val sslContext = ExampleKeystore.sslContext()
  private val f: BufferPipelineBuilder = { _ =>
    val eng = sslContext.createSSLEngine()
    eng.setUseClientMode(false)

    ALPN.put(eng, new ServerProvider)
    TrunkBuilder(new SSLStage(eng)).cap(ExampleService.http2Stage(None, 16*1024))
  }

  private val factory = new NIO1SocketServerChannelFactory(f, workerThreads = Consts.poolSize)

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object Http2Server {
  type Http2Meg = NodeMsg.Http2Msg[Seq[(String, String)]]

  def main(args: Array[String]) {
    println("Hello world!")
    new Http2Server(4430).run()
  }
}
