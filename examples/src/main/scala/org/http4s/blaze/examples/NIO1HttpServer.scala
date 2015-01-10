package org.http4s.blaze.examples

import java.net.InetSocketAddress

import org.http4s.blaze.channel._
import org.http4s.blaze.channel.nio1.NIO1SocketServerChannelFactory
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.pipeline.stages.monitors.IntervalConnectionMonitor

import scala.concurrent.duration._

class NIO1HttpServer(port: Int) {

  private val status = new IntervalConnectionMonitor(2.seconds)
  private val f: BufferPipelineBuilder =
    status.wrapBuilder { _ => LeafBuilder(ExampleService.http1Stage(Some(status), 10*1024)) }

  private val factory = new NIO1SocketServerChannelFactory(f, workerThreads = 6)

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object NIO1HttpServer {
  def main(args: Array[String]): Unit = new NIO1HttpServer(8080).run()
}
