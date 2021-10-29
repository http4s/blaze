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
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import org.http4s.blaze.pipeline.MidStage
import org.http4s.blaze.util.{Cancelable, TickWheelExecutor}
import java.util.concurrent.atomic.AtomicReference

abstract class TimeoutStageBase[T](timeout: Duration, exec: TickWheelExecutor)
    extends MidStage[T, T] { stage =>

  import TimeoutStageBase.closedTag

  // Constructor
  require(timeout.isFinite && timeout.toMillis != 0, s"Invalid timeout: $timeout")

  override def name: String = s"${this.getClass.getName} Stage: $timeout"

  // ///////// Private impl bits //////////////////////////////////////////

  private val lastTimeout =
    new AtomicReference[Cancelable](Cancelable.NoopCancel)

  private val killswitch = new Runnable {
    override def run(): Unit = {
      val ex = new TimeoutException(s"Timeout of $timeout triggered. Killing pipeline.")
      closePipeline(Some(ex))
    }
  }

  @tailrec
  final def setAndCancel(next: Cancelable): Unit = {
    val prev = lastTimeout.getAndSet(next)
    if (prev == closedTag && next != closedTag) {
      // woops... we submitted a new cancellation when we were closed!
      next.cancel()
      setAndCancel(closedTag)
    } else prev.cancel()
  }

  // ///////// Pass through implementations ////////////////////////////////

  override def readRequest(size: Int): Future[T] = channelRead(size)

  override def writeRequest(data: T): Future[Unit] = channelWrite(data)

  override def writeRequest(data: collection.Seq[T]): Future[Unit] = channelWrite(data)

  // ///////// Protected impl bits //////////////////////////////////////////

  override protected def stageShutdown(): Unit = {
    setAndCancel(closedTag)
    super.stageShutdown()
  }

  final protected def resetTimeout(): Unit =
    setAndCancel(exec.schedule(killswitch, timeout))

  final protected def cancelTimeout(): Unit =
    setAndCancel(Cancelable.NoopCancel)

  final protected def startTimeout(): Unit = resetTimeout()
}

private object TimeoutStageBase {
  // Represents the situation where the pipeline has been closed.
  val closedTag = new Cancelable {
    override def cancel(): Unit = ()
  }
}
