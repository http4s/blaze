package org.http4s.blaze.http.endtoend

import java.net.InetSocketAddress

import org.http4s.blaze.channel.nio1.NIO1SocketServerGroup
import org.http4s.blaze.http.{HttpServerStageConfig, Http1ServerStage, HttpService}
import org.http4s.blaze.pipeline.LeafBuilder

final class LocalServer private(service: HttpService, port: Int) {
  private val config = HttpServerStageConfig() // just the default config, for now
  val group = NIO1SocketServerGroup.fixedGroup(workerThreads = 2)

  private val ch = group.bind(new InetSocketAddress(port), _ => LeafBuilder(new Http1ServerStage(service, config)))
    .getOrElse(sys.error("Failed to start server."))

  def getAddress: InetSocketAddress = ch.socketAddress

  def close(): Unit = ch.close()
}

object LocalServer {

  def apply(port: Int)(service: HttpService): LocalServer = new LocalServer(service, port)


  def withLocalServer[T](service: HttpService)(f: InetSocketAddress => T): T = {
    val server = new LocalServer(service, 0)
    f(server.getAddress)
    try f(server.getAddress)
    finally server.close()
  }
}
