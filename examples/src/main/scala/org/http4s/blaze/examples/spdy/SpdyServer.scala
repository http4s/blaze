package org.http4s.blaze.examples.spdy

import org.eclipse.jetty.alpn.ALPN

import java.util.concurrent.Executors
import java.nio.channels.AsynchronousChannelGroup
import java.net.InetSocketAddress

import org.http4s.blaze.examples.{Consts, ExampleKeystore}
import org.http4s.blaze.channel._
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.channel.nio2.NIO2SocketServerChannelFactory
import org.http4s.blaze.http.spdy.Spdy3_1FrameCodec
import org.http4s.blaze.pipeline.TrunkBuilder

class SpdyServer(port: Int) {
  val sslContext = ExampleKeystore.sslContext()

  private val f: BufferPipelineBuilder = { _ =>
    val eng = sslContext.createSSLEngine()
    eng.setUseClientMode(false)

    ALPN.put(eng, new ServerProvider)
    TrunkBuilder(new SSLStage(eng)).append(new Spdy3_1FrameCodec).cap(new SpdyHandler(eng))
  }

  val group = AsynchronousChannelGroup.withFixedThreadPool(Consts.poolSize, Executors.defaultThreadFactory())

  private val factory = NIO2SocketServerChannelFactory(f)

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object SpdyServer {
  def main(args: Array[String]) {
    println("Hello world!")
    new SpdyServer(4430).run()
  }
}
