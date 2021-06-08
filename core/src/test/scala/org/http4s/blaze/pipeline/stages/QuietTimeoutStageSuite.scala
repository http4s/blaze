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

import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.Command

import scala.concurrent.Future
import scala.concurrent.duration._

class QuietTimeoutStageSuite extends TimeoutHelpers {
  override def genDelayStage(timeout: Duration): TimeoutStageBase[ByteBuffer] =
    new QuietTimeoutStage[ByteBuffer](timeout)

  test("A QuietTimeoutStage should not timeout with proper intervals") {
    val pipe = makePipeline(Duration.Zero, 10.seconds)

    for {
      _ <- checkFuture(pipe.channelRead())
      _ = pipe.closePipeline(None)
    } yield ()
  }

  test("A QuietTimeoutStage should timeout properly") {
    val pipe = makePipeline(delay = 10.seconds, timeout = 100.milliseconds)
    val result = checkFuture(pipe.channelRead()).failed.map {
      case Command.EOF => true
      case _ => false
    }

    assertFutureBoolean(result)
  }

  test("A QuietTimeoutStage should not timeout if the delay stage is removed") {
    val pipe = makePipeline(2.seconds, 1.second)
    val f = pipe.channelRead()
    pipe.findOutboundStage(classOf[TimeoutStageBase[ByteBuffer]]).get.removeStage()

    for {
      _ <- checkFuture(f)
      _ <- Future(pipe.closePipeline(None))
    } yield ()
  }

  test("A QuietTimeoutStage should not schedule timeouts after the pipeline has been shut down") {
    val pipe = makePipeline(delay = 10.seconds, timeout = 1.seconds)
    val f = pipe.channelRead()
    pipe.closePipeline(None)

    val result = checkFuture(f).failed.map {
      case Command.EOF => true
      case _ => false
    }

    assertFutureBoolean(result)
  }
}
