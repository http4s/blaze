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

package org.http4s.blaze.util

import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.TailStage
import org.http4s.blaze.testkit.BlazeTestSuite

import scala.concurrent.Future
import scala.concurrent.duration._

class StageToolsSuite extends BlazeTestSuite {
  class Boom extends Exception("boom")

  test("StageTools.accumulateAtLeast should return the empty buffer for 0 byte accumulated") {
    val stage = new TailStage[ByteBuffer] {
      def name: String = "TestTailStage"
    }

    assertFuture(StageTools.accumulateAtLeast(0, stage).map(_.remaining()), 0)
  }

  test(
    "StageTools.accumulateAtLeast should accumulate only one buffer if it satisfies the number of bytes required") {
    val buff = ByteBuffer.wrap(Array[Byte](1, 2, 3))
    val stage = new TailStage[ByteBuffer] {
      def name: String = "TestTailStage"

      override def channelRead(size: Int, timeout: Duration): Future[ByteBuffer] =
        Future.successful(buff.duplicate())
    }

    assertFuture(StageTools.accumulateAtLeast(3, stage), buff)
  }

  test("StageTools.accumulateAtLeast should accumulate two buffers") {
    val buff = ByteBuffer.wrap(Array[Byte](1, 2, 3))
    val stage = new TailStage[ByteBuffer] {
      def name: String = "TestTailStage"

      override def channelRead(size: Int, timeout: Duration): Future[ByteBuffer] =
        Future(buff.duplicate()).flatMap(_ => Future(buff.duplicate()))
    }

    val expectedBuff = ByteBuffer.wrap(Array[Byte](1, 2, 3, 1, 2, 3))
    assertFuture(StageTools.accumulateAtLeast(6, stage), expectedBuff)
  }

  test("StageTools.accumulateAtLeast should handle errors in the first read") {
    val stage = new TailStage[ByteBuffer] {
      def name: String = "TestTailStage"

      override def channelRead(size: Int, timeout: Duration): Future[ByteBuffer] =
        Future.failed(new Boom)
    }

    val result = StageTools.accumulateAtLeast(6, stage).failed.map {
      case _: Boom => true
      case _ => false
    }

    assertFutureBoolean(result)
  }

  test("StageTools.accumulateAtLeast should handle errors in the second read") {
    val buff = ByteBuffer.wrap(Array[Byte](1, 2, 3))
    val stage = new TailStage[ByteBuffer] {
      def name: String = "TestTailStage"

      override def channelRead(size: Int, timeout: Duration): Future[ByteBuffer] =
        Future(buff.duplicate()).flatMap(_ => Future.failed(new Boom))
    }

    val result = StageTools.accumulateAtLeast(6, stage).failed.map {
      case _: Boom => true
      case _ => false
    }

    assertFutureBoolean(result)
  }
}
