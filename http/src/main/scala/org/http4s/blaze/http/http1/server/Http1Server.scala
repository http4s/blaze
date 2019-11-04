package org.http4s.blaze.http.http1.server

import java.net.InetSocketAddress

import org.http4s.blaze.channel
import org.http4s.blaze.channel._
import org.http4s.blaze.channel.nio1.NIO1SocketServerGroup
import org.http4s.blaze.channel.nio2.NIO2SocketServerGroup
import org.http4s.blaze.http.{HttpServerStageConfig, _}
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.util.Execution

import scala.concurrent.Future
import scala.util.Try

object Http1Server {
  case class GroupAndChannel(group: ServerChannelGroup, channel: ServerChannel)

  /** Create a new Http1Server */
  def apply(
      service: SocketConnection => Future[HttpService],
      address: InetSocketAddress,
      config: HttpServerStageConfig,
      useNio2: Boolean = false,
      workerThreads: Int = channel.DefaultPoolSize): Try[GroupAndChannel] = {
    val group: ServerChannelGroup =
      if (useNio2)
        NIO2SocketServerGroup.fixedGroup(workerThreads = workerThreads)
      else NIO1SocketServerGroup.fixedGroup(workerThreads = workerThreads)

    val builder = service(_: SocketConnection).map { service =>
      LeafBuilder(new Http1ServerStage(service, config))
    }(Execution.directec)

    val channel = group.bind(address, builder)
    if (channel.isFailure) group.closeGroup()
    channel.map(GroupAndChannel(group, _))
  }
}
