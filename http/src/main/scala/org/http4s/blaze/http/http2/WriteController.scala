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

package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import scala.concurrent.Future

// TODO: writing should be in terms of something that can be turned into a buffer
// but can still be examined since it is currently impossible to introspect even
// the boundaries of where one message starts and another begins.
// This would allow us to prioritize messages (like PING responses).
/** Generic interface used by HTTP2 types to write data */
private trait WriteController {

  /** Register a [[WriteInterest]] with this listener to be invoked later once it is possible to
    * write data to the outbound channel.
    *
    * @param interest
    *   the `WriteListener` with an interest in performing a write operation.
    * @return
    *   true if registration successful, false otherwise
    */
  def registerWriteInterest(interest: WriteInterest): Boolean

  /** Drain any existing messages with the future resolving on completion */
  def close(): Future[Unit]

  /** Queue multiple buffers for writing
    *
    * @return
    *   true if the data was scheduled for writing, false otherwise.
    */
  def write(data: Seq[ByteBuffer]): Boolean

  /** Queue a buffer for writing
    *
    * @return
    *   true if the data was scheduled for writing, false otherwise.
    */
  def write(data: ByteBuffer): Boolean
}
