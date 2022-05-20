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

import org.http4s.blaze.pipeline.MidStage
import scala.concurrent.Future

/** Holds a single element that when read, will eject itself from the pipeline.
  * @note
  *   There is an intrinsic race condition between this stage removing itself and write commands.
  *   Therefore, pipeline reads and writes must be performed in a thread safe manner until the first
  *   read has completed.
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
