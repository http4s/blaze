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
import blaze.util.BogusKeystore
import java.security.KeyStore
import javax.net.ssl.{SSLContext, KeyManagerFactory}
import blaze.pipeline.stages.SSLStage
import blaze.channel.nio2.NIO2ServerChannelFactory

/**
 * @author Bryce Anderson
 *         Created on 1/10/14
 */
class BlazeExample(port: Int) {

  val sslContext = {
    val ksStream = BogusKeystore.asInputStream()
    val ks = KeyStore.getInstance("JKS")
    ks.load(ksStream, BogusKeystore.getKeyStorePassword)

    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, BogusKeystore.getCertificatePassword)

    val context = SSLContext.getInstance("SSL")

    context.init(kmf.getKeyManagers(), null, null)
    context
  }

  val route = new ExampleRoute().apply()

  private val f: PipeFactory = { b =>
  val eng = sslContext.createSSLEngine()
  eng.setUseClientMode(false)

  b.append(new SSLStage(eng)).cap(new Http4sStage(URITranslation.translateRoot("/http4s")(route)))
  }

  val group = AsynchronousChannelGroup.withFixedThreadPool(10, java.util.concurrent.Executors.defaultThreadFactory())

  private val factory = new NIO2ServerChannelFactory(f)

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object BlazeExample {
  def main(args: Array[String]): Unit = new BlazeExample(4430).run()
}
