package org.http4s.blaze.util

import java.nio.ByteBuffer
import org.log4s.getLogger


object ScratchBuffer {
  private[this] val logger = getLogger
  val localBuffer = new ThreadLocal[ByteBuffer]

  def getScratchBuffer(size: Int): ByteBuffer = {
    val b = localBuffer.get()

    if (b == null || b.capacity() < size) {
      logger.trace(s"Allocating thread local ByteBuffer($size)")
      val b = BufferTools.allocate(size)
      localBuffer.set(b)
      b
    } else {
      b.clear()
      b
    }
  }

  def clearBuffer(): Unit = {
    logger.trace("Removing thread local ByteBuffer")
    localBuffer.remove()
  }
}
