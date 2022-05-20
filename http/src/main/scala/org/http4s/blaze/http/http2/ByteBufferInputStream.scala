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

import java.io.{IOException, InputStream}
import java.nio.ByteBuffer

/** Wrap a `ByteBuffer` in an `InputStream` interface. This is just an adapter to work with the
  * twitter hpack implementation. I would really like to get rid of it.
  */
private final class ByteBufferInputStream(buffer: ByteBuffer) extends InputStream {
  private[this] var markSize = -1

  override def read(): Int =
    if (buffer.hasRemaining) {
      markSize -= 1
      buffer.get() & 0xff
    } else -1

  override def read(b: Array[Byte], off: Int, len: Int): Int =
    if (!buffer.hasRemaining) -1
    else {
      val readSize = math.min(len, buffer.remaining)
      markSize -= readSize
      // the buffer.get call will check the array bounds
      buffer.get(b, off, readSize)
      readSize
    }

  override def available(): Int = buffer.remaining

  override def mark(readlimit: Int): Unit = {
    markSize = readlimit
    buffer.mark()
    ()
  }

  override def reset(): Unit =
    if (markSize >= 0) {
      markSize = -1
      buffer.reset()
      ()
    } else throw new IOException("Invalid mark")

  override def markSupported(): Boolean = true
}
