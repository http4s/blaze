package org.http4s.blaze.http.http20

import java.io.{IOException, InputStream}
import java.nio.ByteBuffer

/** Wrap a `ByteBuffer` in an `InputStream` interface.
  * This is just an adapter to work with the twitter hpack
  * implementation. I would really like to get rid of it.
  */
final private class ByteBufferInputStream(buffer: ByteBuffer) extends InputStream {

  private var markSize = 0

  // TODO: I cant make this block until more data becomes available..
  override def read(): Int = {
    if (buffer.hasRemaining()) {
      markSize -= 1
      buffer.get() & 0xff
    }
    else -1
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
