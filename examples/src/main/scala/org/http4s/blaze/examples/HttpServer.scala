package org.http4s.blaze.examples

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

import org.http4s.blaze.channel._
import org.http4s.blaze.channel.nio1.NIO1SocketServerGroup
import org.http4s.blaze.channel.nio2.NIO2SocketServerGroup
import org.http4s.blaze.http.HttpServerConfig
import org.http4s.blaze.pipeline.stages.SSLStage
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.pipeline.stages.monitors.IntervalConnectionMonitor

import scala.concurrent.duration._

class HttpServer(factory: ServerChannelGroup, port: Int, ports: Int*)
                (trans: LeafBuilder[ByteBuffer] => LeafBuilder[ByteBuffer] = identity(_)) {

  private val status = new IntervalConnectionMonitor(2.seconds)
  private val config = HttpServerConfig() // just the default config

  def run(): Seq[ServerChannel] = {
    (port +: ports).map { i =>
      val ref = new AtomicReference[ServerChannel](null)
      val f: BufferPipelineBuilder =
        status.wrapBuilder { _ => trans(LeafBuilder(ExampleService.http1Stage(Some(status), config, Some(ref)))) }

      val ch = factory.bind(new InetSocketAddress(i), f).getOrElse(sys.error("Failed to start server."))
      ref.set(ch)
      ch
    }
  }
}

/** Opens a demo server on two ports, 8080 and 8081 */
object NIO1HttpServer {
  def main(args: Array[String]): Unit = {
    val f = NIO1SocketServerGroup.fixedGroup(workerThreads = Consts.poolSize)
    new HttpServer(f, 8080, 8081)()
      .run()
      .foreach(_.join())

    println("Finished.")
  }
}

object NIO2HttpServer {
  def main(args: Array[String]): Unit = {
    val f = NIO2SocketServerGroup()
    new HttpServer(f, 8080, 8081)()
      .run()
      .foreach(_.join())

    println("Finished.")
  }
}

object SSLHttpServer {
  def main(args: Array[String]): Unit = {
    val sslContext = ExampleKeystore.sslContext()
    val f = NIO1SocketServerGroup.fixedGroup(workerThreads = Consts.poolSize)
    new HttpServer(f, 4430)({ builder =>
      val eng = sslContext.createSSLEngine()
      eng.setUseClientMode(false)
      builder.prepend(new SSLStage(eng, 100*1024))
    })
      .run()
      .foreach(_.join())

    println("Finished.")
  }
}

object ClientAuthSSLHttpServer {

  def main(args: Array[String]): Unit = {
    val sslContext = ExampleKeystore.clientAuthSslContext()
    val f = NIO1SocketServerGroup.fixedGroup(workerThreads = Consts.poolSize)
    new HttpServer(f, 4430)({ builder =>
        val eng = sslContext.createSSLEngine()
        eng.setUseClientMode(false)
        eng.setNeedClientAuth(true)
        builder.prepend(new SSLStage(eng, 100*1024))
      })
      .run()
      .foreach(_.join())

    println("Finished.")
  }
}
