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

import org.http4s.blaze.pipeline.Command
import scala.concurrent.{Future, Promise}

class GatheringSeqHead[O](items: Seq[O]) extends SeqHead[O](items) {
  private var result: Option[Promise[Seq[O]]] = None

  override protected def doClosePipeline(cause: Option[Throwable]): Unit =
    this.synchronized {
      result match {
        case None => sys.error("Invalid state!")
        case Some(p) =>
          p.success(this.results)
          ()
      }
    }

  def go(): Future[Seq[O]] = {
    val p = this.synchronized {
      assert(result.isEmpty, s"Cannot use ${this.getClass.getSimpleName} more than once")
      val p = Promise[Seq[O]]()
      result = Some(p)

      sendInboundCommand(Command.Connected)
      p
    }

    p.future
  }
}
