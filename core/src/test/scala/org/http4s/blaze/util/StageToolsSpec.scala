/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
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
