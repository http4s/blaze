package blaze.pipeline.stages.http.websocket

import blaze.pipeline.{PipelineBuilder, Command, TailStage}
import blaze.pipeline.stages.http.websocket.WebSocketDecoder.WebSocketFrame
import blaze.util.Execution.trampoline

import scala.util.{Failure, Success}
import blaze.pipeline.stages.SerializingStage

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
          sendOutboundCommand(Command.Shutdown)
        }

      case Failure(t) =>
        logger.error("error on Websocket read loop", t)
        sendOutboundCommand(Command.Shutdown)
    }(trampoline)
  }

  override protected def stageStartup(): Unit = {
    super.stageStartup()
    _wsLoop()
  }
}

object WSStage {
  def segment(stage: WSStage): TailStage[WebSocketFrame] = {
    PipelineBuilder(new SerializingStage[WebSocketFrame]).cap(stage)
  }
}
