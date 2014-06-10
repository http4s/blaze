package org.http4s.blaze.pipeline.stages

import org.specs2.mutable.Specification
import scala.concurrent.duration._
import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage}
import java.nio.ByteBuffer
import org.specs2.time.NoTimeConversions
import scala.concurrent.{Future, Await}

/**
 * Created by Bryce Anderson on 6/9/14.
 */
class TimeoutStageSpec extends Specification with NoTimeConversions {

  def newBuff = ByteBuffer.wrap("Foo".getBytes())

  def checkBuff(buff: ByteBuffer) = {
    val arr = new Array[Byte]("Foo".getBytes().length)
    buff.get(arr)
    new String(arr) should_== "Foo"
  }

  def checkFuture(f: Future[ByteBuffer], timeout: Duration = 2.seconds) = {
    val r = Await.result(f, timeout)
    checkBuff(r)
  }

  def slow(duration: Duration) = new DelayHead[ByteBuffer](duration) { def next() = newBuff }
  def bufferTail = new TailStage[ByteBuffer] { def name = "ByteBuffer Tail" }

  def makePipeline(delay: Duration, timeout: Duration): TailStage[ByteBuffer] = {
    val leaf = bufferTail
    LeafBuilder(leaf)
      .prepend(new TimeoutStage[ByteBuffer](timeout))
      .base(slow(delay))

    leaf
  }

  "A TimeoutStage" should {
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
      pipe.findOutboundStage(classOf[TimeoutStage[ByteBuffer]]).get.removeStage
      val r = checkFuture(f, 5.second)
      pipe.sendOutboundCommand(Command.Disconnect)
      r
    }
  }

}
