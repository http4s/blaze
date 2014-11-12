package org.http4s.blaze.pipeline.stages

import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.Command

import scala.concurrent.duration._

class QuietTimeoutStageSpec extends TimeoutHelpers {

  override def genDelayStage(timeout: Duration): TimeoutStageBase[ByteBuffer] = new QuietTimeoutStage[ByteBuffer](timeout)

  "A QuietTimeoutStage" should {
    "not timeout with propper intervals" in {
      val pipe = makePipeline(Duration.Zero, 10.seconds)

      val r = checkFuture(pipe.channelRead())
      pipe.sendOutboundCommand(Command.Disconnect)
      r
    }

    "timeout properly" in {
      val pipe = makePipeline(10.seconds, 100.milliseconds)
      checkFuture(pipe.channelRead(), 5.second) should throwA[Command.EOF.type]
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
