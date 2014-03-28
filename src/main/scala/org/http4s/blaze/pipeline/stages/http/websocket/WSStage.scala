package org.http4s.blaze.pipeline.stages.http.websocket

import org.http4s.blaze.pipeline._
import org.http4s.blaze.pipeline.stages.http.websocket.WebSocketDecoder.WebSocketFrame
import org.http4s.blaze.util.Execution.trampoline

import org.http4s.blaze.pipeline.stages.SerializingStage
import scala.util.Failure
import scala.util.Success

/**
 * @author Bryce Anderson
 *         Created on 1/18/14
 */
trait WSStage extends TailStage[WebSocketFrame] {
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
          logger.error("WSStage onMessage threw exception. Shutting down.", t)
          sendOutboundCommand(Command.Disconnect)
        }

      case Failure(t) =>
        logger.error("error on Websocket read loop", t)
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
