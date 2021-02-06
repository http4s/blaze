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

package org.http4s.blaze
package channel

import org.http4s.blaze.pipeline.TailStage
import java.nio.ByteBuffer
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import org.http4s.blaze.pipeline.Command.EOF

class EchoStage extends TailStage[ByteBuffer] {
  def name: String = "EchoStage"

  val msg = "echo: ".getBytes

  private implicit def ec: ExecutionContext = util.Execution.trampoline

  final override def stageStartup(): Unit =
    channelRead().onComplete {
      case Success(buff) =>
        val b = ByteBuffer.allocate(buff.remaining() + msg.length)
        b.put(msg).put(buff).flip()

        // Write it, wait for conformation, and start again
        channelWrite(b).foreach(_ => stageStartup())

      case Failure(EOF) => logger.debug("Channel closed.")
      case Failure(t) => logger.error(t)("Channel read failed")
    }
}
