package blaze.examples.spdy

import javax.net.ssl.{KeyManagerFactory, SSLContext}
import blaze.util.BogusKeystore
import java.security.KeyStore
import blaze.channel._
import blaze.pipeline.stages.SSLStage
import java.nio.channels.AsynchronousChannelGroup
import blaze.channel.nio2.NIO2ServerChannelFactory
import java.net.InetSocketAddress

import org.eclipse.jetty.npn.NextProtoNego

import blaze.pipeline.stages.spdy.SpdyFrameCodec

/**
 * @author Bryce Anderson
 *         Created on 1/27/14
 */
class SpdyServer(port: Int) {
  val sslContext: SSLContext = {
    val ksStream = BogusKeystore.asInputStream()
    val ks = KeyStore.getInstance("JKS")
    ks.load(ksStream, BogusKeystore.getKeyStorePassword)

    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, BogusKeystore.getCertificatePassword)

    val context = SSLContext.getInstance("SSL")

    context.init(kmf.getKeyManagers(), null, null)
    context
  }


  private val f: PipeFactory = { b =>
    val eng = sslContext.createSSLEngine()
    eng.setUseClientMode(false)

    NextProtoNego.put(eng, new ServerProvider)
    b.append(new SSLStage(eng)).append(new SpdyFrameCodec).cap(new SpdyHandler(eng))
  }

  val group = AsynchronousChannelGroup.withFixedThreadPool(10, java.util.concurrent.Executors.defaultThreadFactory())

  private val factory = new NIO2ServerChannelFactory(f)

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object SpdyServer {
  def main(args: Array[String]) {
    println("Hello world!")
    new SpdyServer(4430).run()
  }
}
