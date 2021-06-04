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

import munit.FunSuite
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

class StageSuite extends FunSuite {
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

    assertEquals(Await.result(leaf.channelRead(), 5.seconds), 1)
  }

  test("Support writes") {
    val leaf = regPipeline()

    assertEquals(Await.result(leaf.channelWrite(12), 5.seconds), ())
  }

  test("Support read timeouts") {
    val leaf = slowPipeline()

    val result1 = Try(Await.result(leaf.channelRead(1, 100.milli), 5000.milli))

    result1.fold(
      {
        case _: TimeoutException => ()
        case ex => fail(s"Unexpected exception found $ex")
      },
      _ => fail("Support read timeouts")
    )

    assertEquals(Await.result(leaf.channelRead(1, 10.seconds), 10.seconds), 1)
  }

  test("Support write timeouts") {
    val leaf = slowPipeline()

    val result1 = Try(Await.result(leaf.channelWrite(1, 100.milli), 5000.milli))

    result1.fold(
      {
        case _: TimeoutException => ()
        case ex => fail(s"Unexpected exception found $ex")
      },
      _ => fail("Support write timeouts")
    )

    assertEquals(Await.result(leaf.channelWrite(1, 10.seconds), 10.seconds), ())
  }
}
