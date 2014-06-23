package org.http4s.blaze.pipeline.stages

import org.specs2.mutable.Specification
import scala.concurrent.duration._
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}
import java.nio.ByteBuffer
import org.specs2.time.NoTimeConversions
import scala.concurrent.{Future, Await}

/**
 * Created by Bryce Anderson on 6/9/14.
 */
abstract class TimeoutHelpers extends Specification with NoTimeConversions {
  
  def genDelayStage(timeout: Duration): TimeoutStageBase[ByteBuffer]

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
      .prepend(genDelayStage(timeout))
      .base(slow(delay))

    leaf
  }
}
