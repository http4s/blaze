/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2

import java.io.{IOException, InputStream}
import java.nio.ByteBuffer

/** Wrap a `ByteBuffer` in an `InputStream` interface.
  * This is just an adapter to work with the twitter hpack
  * implementation. I would really like to get rid of it.
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
