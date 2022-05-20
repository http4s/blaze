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

package org.http4s.blaze.channel.nio1

import java.nio.channels.SelectableChannel

/** Facade over NIO1 `SelectableChannel`s
  *
  * The interface adds a way to cleanup resources other than the channel itself.
  */
trait NIO1Channel {

  /** The underlying java `SelectableChannel` */
  val selectableChannel: SelectableChannel

  /** Close this channel, releasing associated resources */
  def close(): Unit
}

object NIO1Channel {
  // Simple proxy implementation
  private[this] final class Impl(val selectableChannel: SelectableChannel) extends NIO1Channel {
    override def close(): Unit = selectableChannel.close()
  }

  /** Construct a basic `NIO1Channel` from any `SelectableChannel` */
  def apply(selectableChannel: SelectableChannel): NIO1Channel =
    new Impl(selectableChannel)
}
