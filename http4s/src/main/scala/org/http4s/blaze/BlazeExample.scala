package org.http4s.examples.blaze

/**
 * @author Bryce Anderson
 *         Created on 1/10/14
 */


import org.http4s.blaze.channel._
import java.nio.channels.AsynchronousChannelGroup
import java.net.InetSocketAddress
import org.http4s.blaze.Http4sStage
import org.http4s.examples.ExampleRoute
import org.http4s.util.middleware.URITranslation
import org.http4s.blaze.util.BogusKeystore
import java.security.KeyStore
import javax.net.ssl.{SSLContext, KeyManagerFactory}
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.channel.nio1.{SocketServerChannelFactory, NIOServerChannelFactory}
import java.nio.ByteBuffer
import org.http4s.blaze.pipeline.LeafBuilder

/**
 * @author Bryce Anderson
 *         Created on 1/10/14
 */
class BlazeExample(port: Int) {

//  val sslContext = {
//    val ksStream = BogusKeystore.asInputStream()
//    val ks = KeyStore.getInstance("JKS")
//    ks.load(ksStream, BogusKeystore.getKeyStorePassword)
//
//    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
//    kmf.init(ks, BogusKeystore.getCertificatePassword)
//
//    val context = SSLContext.getInstance("SSL")
//
//    context.init(kmf.getKeyManagers(), null, null)
//    context
//  }

  val route = new ExampleRoute().apply()

  def f(): LeafBuilder[ByteBuffer] = {
//  val eng = sslContext.createSSLEngine()
//  eng.setUseClientMode(false)

  new Http4sStage(URITranslation.translateRoot("/http4s")(route))
  }

//  val group = AsynchronousChannelGroup.withFixedThreadPool(10, java.util.concurrent.Executors.defaultThreadFactory())

  private val factory = new SocketServerChannelFactory(f, 12, 8*1024)

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object BlazeExample {
  println("Starting Http4s-org.http4s.blaze example")
  def main(args: Array[String]): Unit = new BlazeExample(8080).run()
}
