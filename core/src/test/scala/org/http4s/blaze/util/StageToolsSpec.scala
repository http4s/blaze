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
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

import scala.concurrent.{Await, Awaitable, Future}
import scala.concurrent.duration._

class StageToolsSpec extends Specification with Mockito {
  class Boom extends Exception("boom")

  def await[T](a: Awaitable[T]): T = Await.result(a, 5.seconds)

  "StageTools.accumulateAtLeast" should {
    "Return the empty buffer for 0 byte accumulated" in {
      val stage = mock[TailStage[ByteBuffer]]
      val buf = await(StageTools.accumulateAtLeast(0, stage))
      buf.remaining() must_== 0
    }

    "Accumulate only one buffer if it satisfies the number of bytes required" in {
      val buff = ByteBuffer.wrap(Array[Byte](1, 2, 3))
      val stage = mock[TailStage[ByteBuffer]]
      stage.channelRead(any, any).returns(Future.successful(buff.duplicate()))

      val result = await(StageTools.accumulateAtLeast(3, stage))
      result must_== buff
    }

    "Accumulate two buffers" in {
      val buff = ByteBuffer.wrap(Array[Byte](1, 2, 3))
      val stage = mock[TailStage[ByteBuffer]]
      stage
        .channelRead(any, any)
        .returns(Future.successful(buff.duplicate()), Future.successful(buff.duplicate()))

      val result = await(StageTools.accumulateAtLeast(6, stage))
      result must_== ByteBuffer.wrap(Array[Byte](1, 2, 3, 1, 2, 3))
    }

    "Handle errors in the first read" in {
      val stage = mock[TailStage[ByteBuffer]]
      stage.channelRead(any, any).returns(Future.failed(new Boom))
      await(StageTools.accumulateAtLeast(6, stage)) must throwA[Boom]
    }

    "Handle errors in the second read" in {
      val buff = ByteBuffer.wrap(Array[Byte](1, 2, 3))
      val stage = mock[TailStage[ByteBuffer]]
      stage
        .channelRead(any, any)
        .returns(Future.successful(buff.duplicate()), Future.failed(new Boom))

      await(StageTools.accumulateAtLeast(6, stage)) must throwA[Boom]
    }
  }
}
