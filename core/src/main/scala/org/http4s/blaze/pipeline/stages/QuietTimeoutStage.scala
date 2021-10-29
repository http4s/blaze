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

import org.http4s.blaze.util.Execution._
import org.http4s.blaze.util.TickWheelExecutor

import scala.concurrent.Future
import scala.concurrent.duration.Duration

/** Shut down the pipeline after a period of inactivity */
class QuietTimeoutStage[T](timeout: Duration, exec: TickWheelExecutor = scheduler)
    extends TimeoutStageBase[T](timeout, exec) {
  // //////////////////////////////////////////////////////////////////////////////

  override protected def stageStartup(): Unit = {
    super.stageStartup()
    startTimeout()
  }

  override def readRequest(size: Int): Future[T] = {
    val f = channelRead(size)
    f.onComplete(_ => resetTimeout())(directec)
    f
  }

  override def writeRequest(data: collection.Seq[T]): Future[Unit] = {
    val f = channelWrite(data)
    f.onComplete(_ => resetTimeout())(directec)
    f
  }

  override def writeRequest(data: T): Future[Unit] = {
    val f = channelWrite(data)
    f.onComplete(_ => resetTimeout())(directec)
    f
  }
}
