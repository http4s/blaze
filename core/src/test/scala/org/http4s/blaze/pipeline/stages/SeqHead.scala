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

import org.http4s.blaze.pipeline.HeadStage
import org.http4s.blaze.util.{FutureEOF, FutureUnit}
import scala.concurrent.Future

class SeqHead[O](private var data: Seq[O]) extends HeadStage[O] {
  private val lock = new AnyRef
  private var acc: Vector[O] = Vector.empty

  override protected def doClosePipeline(cause: Option[Throwable]): Unit = ???

  def results: Seq[O] =
    lock.synchronized {
      acc
    }

  def name: String = "SeqHead test HeadStage"

  def readRequest(size: Int): Future[O] =
    lock.synchronized {
      if (!data.isEmpty) {
        val h = data.head
        data = data.tail
        Future.successful(h)
      } else FutureEOF
    }

  def writeRequest(data: O): Future[Unit] =
    lock.synchronized {
      acc :+= data
      FutureUnit
    }
}
