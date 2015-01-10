package org.http4s.blaze.examples

import java.net.InetSocketAddress
import java.nio.channels.AsynchronousChannelGroup

import org.http4s.blaze.channel._
import org.http4s.blaze.channel.nio2.NIO2SocketServerChannelFactory
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.pipeline.stages.monitors.IntervalConnectionMonitor

import scala.concurrent.duration._

class NIO2HttpServer(port: Int) {

  val group = AsynchronousChannelGroup.withFixedThreadPool(6, java.util.concurrent.Executors.defaultThreadFactory())

  private val status = new IntervalConnectionMonitor(10.minutes)
  private val f: BufferPipelineBuilder = _ => LeafBuilder(ExampleService.http1Stage(Some(status), 10*1024))
  private val factory = new NIO2SocketServerChannelFactory(status.wrapBuilder(f))

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object NIO2HttpServer {
  def main(args: Array[String]): Unit = new NIO2HttpServer(8080).run()
}
