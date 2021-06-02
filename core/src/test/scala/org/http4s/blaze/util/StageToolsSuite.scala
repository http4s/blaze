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

import munit.FunSuite
import org.http4s.blaze.pipeline.TailStage

import scala.concurrent.{Await, Awaitable, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class StageToolsSuite extends FunSuite {
  class Boom extends Exception("boom")

  def await[T](a: Awaitable[T]): T = Await.result(a, 5.seconds)

  test("StageTools.accumulateAtLeast should return the empty buffer for 0 byte accumulated") {
    val stage = new TailStage[ByteBuffer] {
      def name: String = "TestTailStage"
    }

    val buf = await(StageTools.accumulateAtLeast(0, stage))

    assertEquals(buf.remaining(), 0)
  }

  test(
    "StageTools.accumulateAtLeast should accumulate only one buffer if it satisfies the number of bytes required") {
    val buff = ByteBuffer.wrap(Array[Byte](1, 2, 3))
    val stage = new TailStage[ByteBuffer] {
      def name: String = "TestTailStage"

      override def channelRead(size: Int, timeout: Duration): Future[ByteBuffer] =
        Future.successful(buff.duplicate())
    }

    val result = await(StageTools.accumulateAtLeast(3, stage))
    assertEquals(result, buff)
  }

  test("StageTools.accumulateAtLeast should accumulate two buffers") {
    val buff = ByteBuffer.wrap(Array[Byte](1, 2, 3))
    val stage = new TailStage[ByteBuffer] {
      def name: String = "TestTailStage"

      override def channelRead(size: Int, timeout: Duration): Future[ByteBuffer] =
        Future(buff.duplicate()).flatMap(_ => Future(buff.duplicate()))
    }

    val result = await(StageTools.accumulateAtLeast(6, stage))
    assertEquals(result, ByteBuffer.wrap(Array[Byte](1, 2, 3, 1, 2, 3)))
  }

  test("StageTools.accumulateAtLeast should handle errors in the first read") {
    val stage = new TailStage[ByteBuffer] {
      def name: String = "TestTailStage"

      override def channelRead(size: Int, timeout: Duration): Future[ByteBuffer] =
        Future.failed(new Boom)
    }

    val result = Try(await(StageTools.accumulateAtLeast(6, stage)))

    result.fold(
      {
        case _: Boom => ()
        case ex => fail(s"Unexpected exception found $ex")
      },
      _ => fail("StageTools.accumulateAtLeast should handle errors in the first read")
    )
  }

  test("StageTools.accumulateAtLeast should handle errors in the second read") {
    val buff = ByteBuffer.wrap(Array[Byte](1, 2, 3))
    val stage = new TailStage[ByteBuffer] {
      def name: String = "TestTailStage"

      override def channelRead(size: Int, timeout: Duration): Future[ByteBuffer] =
        Future(buff.duplicate()).flatMap(_ => Future.failed(new Boom))
    }

    val result = Try(await(StageTools.accumulateAtLeast(6, stage)))

    result.fold(
      {
        case _: Boom => ()
        case ex => fail(s"Unexpected exception found $ex")
      },
      _ => fail("StageTools.accumulateAtLeast should handle errors in the second read")
    )
  }
}
