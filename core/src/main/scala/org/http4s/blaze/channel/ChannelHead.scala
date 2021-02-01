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

package org.http4s.blaze.channel

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import org.http4s.blaze.pipeline.Command._
import org.http4s.blaze.pipeline.HeadStage

private abstract class ChannelHead extends HeadStage[ByteBuffer] {
  import ChannelHead.brokePipeMessages

  /** Filter the error, replacing known "EOF" like errors with EOF */
  protected def checkError(e: Throwable): Throwable =
    e match {
      case EOF => EOF
      case _: ClosedChannelException => EOF
      case e: IOException if brokePipeMessages.contains(e.getMessage) => EOF
      case e: IOException =>
        logger.warn(e)("Channel IOException not known to be a disconnect error")
        EOF

      case e => e
    }
}

object ChannelHead {
  // If the connection is forcibly closed, we might get an IOException with one of the following messages
  private[blaze] val brokePipeMessages = Set(
    "Connection timed out", // Found on Linux NIO1
    "Connection reset by peer", // Found on Linux
    "Connection reset", // Found on Linux, Java 13
    "An existing connection was forcibly closed by the remote host", // Found on Windows
    "Broken pipe", // also found on Linux
    "The specified network name is no longer available.\r\n" // Found on Windows NIO2
  )
}
