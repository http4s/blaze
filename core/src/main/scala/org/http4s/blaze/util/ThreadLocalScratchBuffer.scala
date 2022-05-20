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

package org.http4s.blaze.util

import java.nio.ByteBuffer
import org.log4s.getLogger

/** Create a cache of thread-local ByteBuffer instances useful for scratch purposes.
  *
  * This is an advanced feature and should be used with extreme caution. It is _very_ easy to let
  * one of these buffers escape the current thread, or even be used improperly by the _same_ thread,
  * resulting in data corruption.
  */
private[blaze] final class ThreadLocalScratchBuffer(useDirect: Boolean) {
  private[this] val logger = getLogger
  private val localBuffer = new ThreadLocal[ByteBuffer]

  private[this] def allocate(size: Int): ByteBuffer =
    if (useDirect) ByteBuffer.allocateDirect(size)
    else ByteBuffer.allocate(size)

  /** Get a thread-local scratch buffer
    *
    * The resulting buffer is stored in a ThreadLocal and is thus shared between invocations by a
    * single thread. As such, the resulting buffers must only be used within the current call stack.
    */
  final def getScratchBuffer(size: Int): ByteBuffer = {
    val b = localBuffer.get()

    if (b == null || b.capacity() < size) {
      logger.trace(s"Allocating thread local ByteBuffer($size)")
      val b = allocate(size)
      localBuffer.set(b)
      b
    } else {
      b.clear()
      b
    }
  }

  /** Remove current scratch buffer.
    *
    * Clears the current thread local buffer. This is useful for making them available to GC.
    */
  final def clearScratchBuffer(): Unit = {
    logger.trace("Removing thread local ByteBuffer")
    localBuffer.remove()
  }
}
