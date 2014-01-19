package blaze.examples

import blaze.channel._
import java.nio.channels.AsynchronousChannelGroup
import java.net.InetSocketAddress
import blaze.pipeline.stages.http.{WSResponse, HttpResponse, Response, HttpStage}
import java.nio.ByteBuffer
import scala.concurrent.Future
import blaze.pipeline.{Command, TailStage}
import blaze.pipeline.stages.http.websocket.WebSocketDecoder._
import scala.util.{Failure, Success}

/**
 * @author Bryce Anderson
 *         Created on 1/18/14
 */
class WebSocketServer(port: Int) {
  private val f: PipeFactory = _.cap(new ExampleWebSocketHttpStage)

  val group = AsynchronousChannelGroup.withFixedThreadPool(10, java.util.concurrent.Executors.defaultThreadFactory())

  private val factory = new ServerChannelFactory(f)

  def run(): Unit = factory.bind(new InetSocketAddress(port)).run()
}

object WebSocketServer {
  def main(args: Array[String]): Unit = new WebSocketServer(8080).run()
}

class ExampleWebSocketHttpStage extends HttpStage(10*1024) {
  def handleRequest(method: String, uri: String, headers: Traversable[(String, String)], body: ByteBuffer): Future[Response] = {
    if (headers.exists{ case (k, v) => k.equalsIgnoreCase("Upgrade") && v.equalsIgnoreCase("websocket")}) {
      logger.info(s"Received a websocket request at $uri")
      Future.successful(WSResponse(new SocketStage))
    } else {
      val msg = "Use a websocket!"
      Future.successful(HttpResponse("OK", 200, Nil, ByteBuffer.wrap(msg.getBytes())))
    }
  }
}

class SocketStage extends TailStage[WebSocketFrame] {
  import blaze.util.Execution._

  implicit val ec = trampoline

  def name: String = "WS Stage"


  override protected def stageStartup(): Unit = {
    logger.trace("SocketStage starting up.")
    super.stageStartup()
    channelWrite(Text("Hello! This is an echo websocket"))
      .onSuccess{ case _ => loop() }
  }

  def loop(): Unit = {
    channelRead().onComplete {
      case Success(Text(msg, _)) =>
        logger.trace(s"Received Websocket message: $msg")
        channelWrite(Text("You sent: " + msg)).onSuccess{ case _ => loop() }

      case Success(Close(_)) =>
        logger.trace("Closing websocket channel")
        sendOutboundCommand(Command.Shutdown)

      case Failure(t) =>
        logger.error("error on Websocket read loop", t)
        sendOutboundCommand(Command.Shutdown)

      case m  =>
        logger.trace(s"Received bad message: $m")
        sendOutboundCommand(Command.Shutdown)
    }
  }
}