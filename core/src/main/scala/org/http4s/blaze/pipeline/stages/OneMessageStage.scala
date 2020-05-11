/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.pipeline.stages

import org.http4s.blaze.pipeline.MidStage
import scala.concurrent.Future

/** Holds a single element that when read, will eject itself
  * from the pipeline.
  * @note There is an intrinsic race condition between this stage
  *       removing itself and write commands. Therefore, pipeline
  *       reads and writes must be performed in a thread safe manner
  *       until the first read has completed.
  */
class OneMessageStage[T](element: T) extends MidStage[T, T] {
  override def name: String = "OneMessageStage"

  override def readRequest(size: Int): Future[T] = {
    this.removeStage()
    Future.successful(element)
  }

  override def writeRequest(data: T): Future[Unit] =
    channelWrite(data)

  override def writeRequest(data: collection.Seq[T]): Future[Unit] =
    channelWrite(data)
}
