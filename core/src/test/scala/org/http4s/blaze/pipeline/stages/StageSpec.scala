package org.http4s.blaze.pipeline.stages

import org.specs2.mutable._
import org.specs2.time.NoTimeConversions

import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}
import scala.concurrent.Await
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._


/**
 * @author Bryce Anderson
 *         Created on 2/2/14
 */
class StageSpec extends Specification with NoTimeConversions {
  
  def intTail = new TailStage[Int] { def name = "Int Tail" }
  def slow(duration: Duration) = new DelayHead[Int](duration) { def next() = 1 }

  def regPipeline = {
    val leaf = intTail
    LeafBuilder(leaf).base(slow(Duration.Zero))
    leaf
  }
  
  def slowPipeline = {
    val leaf = intTail
    LeafBuilder(leaf).base(slow(400.milli))
    leaf
  }
  
  "Support reads" in {
    val leaf = regPipeline
    Await.result(leaf.channelRead(), 800.milli) should_== 1
  }

  "Support writes" in {
    val leaf = regPipeline
    Await.result(leaf.channelWrite(12), 800.milli) should_==(())
  }

  "Support read timeouts" in {

    val leaf = slowPipeline
    Await.result(leaf.channelRead(1, 100.milli), 800.milli) must throwA[TimeoutException]

    Await.result(leaf.channelRead(1, 700.milli), 800.milli) should_== 1
  }
  
  "Support write timeouts" in {

    val leaf = slowPipeline
    Await.result(leaf.channelWrite(1, 100.milli), 800.milli) must throwA[TimeoutException]

    Await.result(leaf.channelWrite(1, 700.milli), 800.milli) should_==(())

  }

}
