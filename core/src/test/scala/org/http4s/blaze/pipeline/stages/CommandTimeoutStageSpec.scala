package org.http4s.blaze.pipeline.stages

import java.nio.ByteBuffer
import org.http4s.blaze.pipeline.Command
import scala.concurrent.duration._

/**
 * Created by Bryce Anderson on 6/23/14.
 */
class CommandTimeoutStageSpec extends TimeoutHelpers {
  override def genDelayStage(timeout: Duration) = new CommandTimeoutStage[ByteBuffer](timeout)

  "A CommandTimeoutStage" should {
    "not timeout with propper intervals" in {
      val pipe = makePipeline(Duration.Zero, 10.seconds)
      pipe.sendOutboundCommand(CommandTimeoutStage.TimeoutBegin)
      val r = checkFuture(pipe.channelRead())
      pipe.sendOutboundCommand(Command.Disconnect)
      r
    }

    "timeout properly" in {
      val pipe = makePipeline(10.seconds, 100.milliseconds)
      pipe.sendOutboundCommand(CommandTimeoutStage.TimeoutBegin)
      checkFuture(pipe.channelRead(), 5.second) should throwA[Command.EOF.type]
    }

    "cancel a timeout properly" in {
      val pipe = makePipeline(2.seconds, 1.seconds)
      val f = pipe.channelRead()
      pipe.sendOutboundCommand(CommandTimeoutStage.TimeoutBegin)
      pipe.sendOutboundCommand(CommandTimeoutStage.TimeoutCancel)
      val r = checkFuture(f, 5.second)
      pipe.sendOutboundCommand(Command.Disconnect)
      r
    }

    "not timeout if the delay stage is removed" in {
      val pipe = makePipeline(2.seconds, 1.second)
      val f = pipe.channelRead()
      pipe.findOutboundStage(classOf[TimeoutStageBase[ByteBuffer]]).get.removeStage
      val r = checkFuture(f, 5.second)
      pipe.sendOutboundCommand(Command.Disconnect)
      r
    }
  }

}
