package org.http4s.blaze.util

import java.nio.ByteBuffer
import org.log4s.getLogger

// TODO: this is dangerous and should be removed
abstract class ScratchBuffer {
  private[this] val logger = getLogger
  private val localBuffer = new ThreadLocal[ByteBuffer]

  final def getScratchBuffer(size: Int): ByteBuffer = {
    val b = localBuffer.get()

    if (b == null || b.capacity() < size) {
      logger.trace(s"Allocating thread local ByteBuffer($size)")
      val b = BufferTools.allocateHeap(size)
      localBuffer.set(b)
      b
    } else {
      b.clear()
      b
    }
  }

  final def clearScratchBuffer(): Unit = {
    logger.trace("Removing thread local ByteBuffer")
    localBuffer.remove()
  }
}
