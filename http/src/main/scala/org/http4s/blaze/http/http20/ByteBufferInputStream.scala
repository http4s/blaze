package org.http4s.blaze.http.http20

import java.io.{IOException, InputStream}
import java.nio.ByteBuffer

final class ByteBufferInputStream(buffer: ByteBuffer) extends InputStream {

  private var markSize = 0

  override def read(): Int = {
    markSize -= 1
    buffer.get()
  }

  override def read(b: Array[Byte], off: Int, len: Int): Int = {
    if (buffer.remaining() == 0) -1
    else {
      val readSize = math.min(len, buffer.remaining())
      markSize -= readSize
      buffer.get(b, off, readSize)
      readSize
    }

  }

  override def available(): Int = buffer.remaining()

  override def mark(readlimit: Int): Unit = {
    markSize = readlimit
    buffer.mark()
  }

  override def reset(): Unit = {
    if (markSize > 0) {
      markSize = 0
      buffer.reset()
    }
    else throw new IOException("Invalid mark")
  }

  override def markSupported(): Boolean = true
}
