package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}
import scala.concurrent.Await
import java.util.concurrent.TimeoutException
import org.scalatest.{Matchers, WordSpec}
import scala.concurrent.duration._

/**
 * @author Bryce Anderson
 *         Created on 2/2/14
 */
class StageSpec extends WordSpec with Matchers {
  
  def intTail = new TailStage[Int] { def name = "Int Tail" }
  def slow(duration: Duration) = new DelayHead[Int](duration) { def next() = 1 }

  def regPipeline = {
    val leaf = intTail
    LeafBuilder(leaf).base(slow(Duration.Zero))
    leaf
  }
  
  def slowPipeline = {
    val leaf = intTail
    LeafBuilder(leaf).base(slow(200.milli))
    leaf
  }
  
  "Support reads" in {
    val leaf = regPipeline
    Await.result(leaf.channelRead(), 800.milli) should equal(1)
  }

  "Support writes" in {
    val leaf = regPipeline
    Await.result(leaf.channelWrite(12), 800.milli) should equal("done")
  }

  "Support read timeouts" in {

    val leaf = slowPipeline
    a[TimeoutException] should be thrownBy Await.result(leaf.channelRead(1, 100.milli), 800.milli)

    Await.result(leaf.channelRead(1, 700.milli), 800.milli) should equal(1)
  }
  
  "Support write timeouts" in {

    val leaf = slowPipeline
    a[TimeoutException] should be thrownBy Await.result(leaf.channelWrite(1, 100.milli), 800.milli)

    Await.result(leaf.channelWrite(1, 700.milli), 800.milli) should equal("done")

  }

}
