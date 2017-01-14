package org.http4s.blaze.examples

import org.http4s.blaze.http._
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.http4s.blaze.http.websocket.WSStage
import org.http4s.blaze.channel._
import org.http4s.websocket.WebsocketBits._
import org.http4s.websocket.WebsocketHandshake
import org.http4s.blaze.channel.nio2.NIO2SocketServerGroup
import java.nio.channels.AsynchronousChannelGroup
import java.net.InetSocketAddress
import java.util.concurrent.Executors

import org.http4s.blaze.util.Execution
import org.log4s.getLogger

import scala.concurrent.Future

class WebSocketServer(port: Int) {
  private val f: BufferPipelineBuilder = _ => LeafBuilder(new ExampleWebSocketHttpServerStage)

  val group = AsynchronousChannelGroup.withFixedThreadPool(Consts.poolSize, Executors.defaultThreadFactory())

  private val factory = NIO2SocketServerGroup()

  def run(): ServerChannel = factory.bind(new InetSocketAddress(port), f).getOrElse(sys.error("Failed to bind server"))
}

object WebSocketServer {
  def main(args: Array[String]): Unit = new WebSocketServer(8080).run().join()
}

/** this stage can be seen as the "route" of the example. It handles requests and returns responses */
class ExampleWebSocketHttpServerStage
  extends HttpServerStage(ExampleWebSocketHttpServerStage.handleRequest, HttpServerConfig())

/** This represents the actual web socket interactions */
class SocketStage extends WSStage {

  def onMessage(msg: WebSocketFrame): Unit = msg match {
    case Text(msg, _) =>
      channelWrite(Text("You sent: " + msg))
      channelWrite(Text("this is a second message which will get queued safely!"))

    case Binary(msg, _) =>


    case Close(_) => sendOutboundCommand(Command.Disconnect)

  }

  override protected def stageStartup(): Unit = {
    logger.debug("SocketStage starting up.")
    super.stageStartup()
    channelWrite(Text("Hello! This is an echo websocket"))
  }
}

object ExampleWebSocketHttpServerStage {
  private val logger = getLogger

  def handleRequest(request: HttpRequest): Future[ResponseBuilder] =
  Future.successful {
    if (WebsocketHandshake.isWebSocketRequest(request.headers)) {
      logger.info(s"Received a websocket request at ${request.uri}")

      // Note the use of WSStage.segment. This makes a pipeline segment that includes a serializer so we
      // can safely write as many messages as we want without worrying about clashing with pending writes
      WSResponseBuilder(WSStage.bufferingSegment(new SocketStage))
    }
    else RouteAction.Ok("Use a websocket!\n" + request.uri)
  }
}