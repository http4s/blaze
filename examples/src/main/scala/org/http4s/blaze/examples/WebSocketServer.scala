package org.http4s.blaze.examples

import org.http4s.blaze.channel._
import java.nio.channels.AsynchronousChannelGroup
import java.net.InetSocketAddress
import org.http4s.blaze.pipeline.stages.http.{WSResponse, SimpleHttpResponse, Response, HttpServerStage}
import java.nio.ByteBuffer
import scala.concurrent.Future
import org.http4s.blaze.pipeline.{LeafBuilder, Command}
import org.http4s.blaze.http.websocket.WebSocketDecoder._
import org.http4s.blaze.http.websocket.{ServerHandshaker, WSStage}
import org.http4s.blaze.channel.nio2.NIO2ServerChannelFactory
import org.log4s.getLogger

/**
 * @author Bryce Anderson
 *         Created on 1/18/14
 */
class WebSocketServer(port: Int) {
  private val f: BufferPipelineBuilder = _ => LeafBuilder(new ExampleWebSocketHttpServerStage)

  val group = AsynchronousChannelGroup.withFixedThreadPool(10, java.util.concurrent.Executors.defaultThreadFactory())

  private val factory = new NIO2ServerChannelFactory(f)

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object WebSocketServer {
  def main(args: Array[String]): Unit = new WebSocketServer(8080).run()
}

/** this stage can be seen as the "route" of the example. It handles requests and returns responses */
class ExampleWebSocketHttpServerStage extends HttpServerStage(10*1024) {
  private[this] val logger = getLogger

  def handleRequest(method: String, uri: String, headers: Seq[(String, String)], body: ByteBuffer): Future[Response] = {
    if (ServerHandshaker.isWebSocketRequest(headers)) {
      logger.info(s"Received a websocket request at $uri")

      // Note the use of WSStage.segment. This makes a pipeline segment that includes a serializer so we
      // can safely write as many messages as we want without worrying about clashing with pending writes
      Future.successful(WSResponse(WSStage.bufferingSegment(new SocketStage)))
    } else Future.successful(SimpleHttpResponse.Ok("Use a websocket!\n" + uri))
  }
}

/** This represents the actual web socket interactions */
class SocketStage extends WSStage {
  private[this] val logger = getLogger

  def onMessage(msg: WebSocketFrame): Unit = msg match {
    case Text(msg, _) =>
      channelWrite(Text("You sent: " + msg))
      channelWrite(Text("this is a second message which will get queued safely!"))

    case Binary(msg, _) =>


    case Close(_) => sendOutboundCommand(Command.Disconnect)

  }

  override protected def stageStartup(): Unit = {
    logger.trace("SocketStage starting up.")
    super.stageStartup()
    channelWrite(Text("Hello! This is an echo websocket"))
  }
}