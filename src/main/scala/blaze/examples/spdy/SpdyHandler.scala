package blaze.examples.spdy

import blaze.pipeline.{Command, TailStage}
import blaze.pipeline.stages.spdy.SpdyFrame
import javax.net.ssl.SSLEngine
import scala.util.{Failure, Success}
import blaze.pipeline.Command.EOF

import scala.concurrent.ExecutionContext.Implicits.global

/**
 * @author Bryce Anderson
 *         Created on 1/27/14
 */
class SpdyHandler(eng: SSLEngine) extends TailStage[SpdyFrame] {
  def name: String = "SpdyStage"


  override protected def stageStartup(): Unit = {
    logger.info("Starting SPDYHandler")
    readLoop()
  }

  def readLoop(): Unit = {
    channelRead().onComplete {
      case Success(frame) =>
        logger.info("Got spdy frame: " + frame)
        readLoop()

      case Failure(EOF) =>
        logger.info("End of spdy stream")
        stageShutdown()

      case Failure(t) =>
        logger.error("Stream failure. Shutting down", t)
        sendOutboundCommand(Command.Shutdown)
        stageShutdown()
    }


  }

  override protected def stageShutdown(): Unit = super.stageShutdown()
}
