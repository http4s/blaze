package org.http4s.blaze.examples

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

import org.http4s.blaze.channel
import org.http4s.blaze.channel._
import org.http4s.blaze.channel.nio1.NIO1SocketServerGroup
import org.http4s.blaze.channel.nio2.NIO2SocketServerGroup
import org.http4s.blaze.http.HttpServerStageConfig
import org.http4s.blaze.http.http1.server.Http1ServerStage
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.pipeline.stages.monitors.IntervalConnectionMonitor

import scala.concurrent.Future
import scala.concurrent.duration._

class Http1ServerExample(factory: ServerChannelGroup, port: Int)(
    trans: LeafBuilder[ByteBuffer] => LeafBuilder[ByteBuffer] = identity(_)) {
  private val status = new IntervalConnectionMonitor(2.seconds)
  private val config = HttpServerStageConfig() // just the default config

  def run(): ServerChannel = {
    val ref = new AtomicReference[ServerChannel](null)
    val f: SocketPipelineBuilder =
      status.wrapBuilder { _ =>
        val stage =
          new Http1ServerStage(ExampleService.service(Some(status), Some(ref)), config)
        Future.successful(trans(LeafBuilder(stage)))
      }

    val address = new InetSocketAddress(port)
    val ch =
      factory.bind(address, f).getOrElse(sys.error("Failed to start server."))
    ref.set(ch)
    ch
  }
}

/** Opens a demo server on ports 8080 */
object NIO1HttpServer {
  def main(args: Array[String]): Unit = {
    val f =
      NIO1SocketServerGroup.fixedGroup(workerThreads = channel.DefaultPoolSize)
    new Http1ServerExample(f, 8080)()
      .run()
      .join()

    println("Finished.")
  }
}

object NIO2HttpServer {
  def main(args: Array[String]): Unit = {
    val f = NIO2SocketServerGroup()
    new Http1ServerExample(f, 8080)()
      .run()
      .join()

    println("Finished.")
  }
}

object SSLHttpServer {
  def main(args: Array[String]): Unit = {
    val sslContext = ExampleKeystore.sslContext()
    val f =
      NIO1SocketServerGroup.fixedGroup(workerThreads = channel.DefaultPoolSize)
    new Http1ServerExample(f, 4430)({ builder =>
      val eng = sslContext.createSSLEngine()
      eng.setUseClientMode(false)
      builder.prepend(new SSLStage(eng, 100 * 1024))
    }).run()
      .join()

    println("Finished.")
  }
}

object ClientAuthSSLHttpServer {

  def main(args: Array[String]): Unit = {
    val sslContext = ExampleKeystore.clientAuthSslContext()
    val f =
      NIO1SocketServerGroup.fixedGroup(workerThreads = channel.DefaultPoolSize)
    new Http1ServerExample(f, 4430)({ builder =>
      val eng = sslContext.createSSLEngine()
      eng.setUseClientMode(false)
      eng.setNeedClientAuth(true)
      builder.prepend(new SSLStage(eng, 100 * 1024))
    }).run()
      .join()

    println("Finished.")
  }
}
