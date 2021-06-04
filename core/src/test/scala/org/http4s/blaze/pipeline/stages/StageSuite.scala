/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.pipeline.stages

import java.util.concurrent.TimeoutException

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}

import scala.concurrent.duration._

class StageSuite extends CatsEffectSuite {
  private def intTail = new TailStage[Int] { def name = "Int Tail" }
  private def slow(duration: Duration) = new DelayHead[Int](duration) { def next() = 1 }

  private def regPipeline() = {
    val leaf = intTail
    LeafBuilder(leaf).base(slow(Duration.Zero))
    leaf
  }

  private def slowPipeline() = {
    val leaf = intTail
    LeafBuilder(leaf).base(slow(5000.milli))
    leaf
  }

  test("Support reads") {
    val leaf = regPipeline()

    assertIO(IO.fromFuture(IO(leaf.channelRead()).timeout(5.seconds)), 1)
  }

  test("Support writes") {
    val leaf = regPipeline()

    assertIO(IO.fromFuture(IO(leaf.channelWrite(12)).timeout(5.seconds)), ())
  }

  test("Support read timeouts") {
    val leaf = slowPipeline()

    val result1 =
      IO.fromFuture(IO(leaf.channelRead(1, 100.milli)).timeout(5000.millis)).attempt.map {
        case Left(err) =>
          err match {
            case _: TimeoutException => true
            case _ => false
          }

        case Right(_) => false
      }

    assertIOBoolean(result1) *>
      assertIO(IO.fromFuture(IO(leaf.channelRead(1, 10.seconds)).timeout(10.seconds)), 1)
  }

  test("Support write timeouts") {
    val leaf = slowPipeline()

    val result1 =
      IO.fromFuture(IO(leaf.channelWrite(1, 100.milli)).timeout(5000.millis)).attempt.map {
        case Left(err) =>
          err match {
            case _: TimeoutException => true
            case _ => false
          }

        case Right(_) => false
      }

    assertIOBoolean(result1) *>
      assertIO(IO.fromFuture(IO(leaf.channelWrite(1, 10.seconds)).timeout(10.seconds)), ())
  }
}
