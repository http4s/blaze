/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.util.Execution._
import org.http4s.blaze.util.TickWheelExecutor

import scala.concurrent.Future
import scala.concurrent.duration.Duration

/** Shut down the pipeline after a period of inactivity */
class QuietTimeoutStage[T](timeout: Duration, exec: TickWheelExecutor = scheduler)
    extends TimeoutStageBase[T](timeout, exec) {
  ////////////////////////////////////////////////////////////////////////////////

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
