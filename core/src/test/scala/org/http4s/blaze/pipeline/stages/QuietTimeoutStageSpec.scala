package org.http4s.blaze.pipeline
package stages

import java.nio.ByteBuffer
import org.http4s.blaze.util.TickWheelExecutor
import org.specs2.specification.BeforeAfterAll
import scala.concurrent.duration._

class QuietTimeoutStageSpec extends TimeoutHelpers with BeforeAfterAll {

  var scheduler: TickWheelExecutor = null

  override def beforeAll() = { scheduler = new TickWheelExecutor() }
  override def afterAll() = scheduler.shutdown()

  override def genDelayStage(timeout: FiniteDuration): TimeoutStage[String, String] =
    QuietTimeoutStage[String](timeout, scheduler)

  "A QuietTimeoutStage" should {
    "not timeout with propper intervals" in {
      val pipe = makePipeline(Duration.Zero, 10.seconds)

      val r = checkFuture(pipe.channelRead())
      pipe.closePipeline(None)
      r
    }

    "timeout properly" in {
      val pipe = makePipeline(delay = 10.seconds, timeout = 100.milliseconds)
      checkFuture(pipe.channelRead(), 5.second) should throwA[Command.EOF.type]
      isTimedOut(pipe) must beTrue
    }

    "not schedule timeouts after the pipeline has been shut down" in {
      val pipe = makePipeline(delay = 10.seconds, timeout = 1.second)
      val f = pipe.channelRead()
      pipe.closePipeline(None)
      checkFuture(f, 5.second) should throwA[Command.EOF.type]
      isTimedOut(pipe) must beFalse
    }
  }

  private def isTimedOut[A](pipe: Tail[A]) = {
    pipe.findOutboundStage(classOf[QuietTimeoutStage[_]]).get.timedOut.value.isDefined
  }
}
