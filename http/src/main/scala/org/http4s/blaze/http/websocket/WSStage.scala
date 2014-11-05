package org.http4s.blaze.http.websocket

import org.http4s.blaze.pipeline._
import org.http4s.blaze.http.websocket.WebSocketDecoder.WebSocketFrame
import org.http4s.blaze.util.Execution.trampoline

import org.http4s.blaze.pipeline.stages.SerializingStage
import scala.util.Failure
import scala.util.Success
import org.log4s.getLogger

/**
 * @author Bryce Anderson
 *         Created on 1/18/14
 */
trait WSStage extends TailStage[WebSocketFrame] {
  private[this] val logger = getLogger

  def name: String = "WebSocket Stage"

  def onMessage(msg: WebSocketFrame): Unit

  /////////////////////////////////////////////////////////////

  private def _wsLoop(): Unit = {
    channelRead().onComplete {
      case Success(msg) =>
        logger.trace(s"Received Websocket message: $msg")
        try {
          onMessage(msg)
          _wsLoop()
        }
        catch {case t: Throwable =>
          logger.error(t)("WSStage onMessage threw exception. Shutting down.")
          sendOutboundCommand(Command.Disconnect)
        }

      case Failure(t) =>
        logger.error(t)("error on Websocket read loop")
        sendOutboundCommand(Command.Disconnect)
    }(trampoline)
  }

  override protected def stageStartup(): Unit = {
    super.stageStartup()
    _wsLoop()
  }
}

object WSStage {
  def bufferingSegment(stage: WSStage): LeafBuilder[WebSocketFrame] = {
    TrunkBuilder(new SerializingStage[WebSocketFrame]).cap(stage)
  }
}
