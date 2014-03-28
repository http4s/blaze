package org.http4s.blaze.util

import com.typesafe.scalalogging.slf4j.Logging
import java.nio.ByteBuffer

/**
 * @author Bryce Anderson
 *         Created on 1/26/14
 */
object ScratchBuffer extends Logging {
  val localBuffer = new ThreadLocal[ByteBuffer]

  def getScratchBuffer(size: Int): ByteBuffer = {
    val b = localBuffer.get()

    if (b == null || b.capacity() < size) {
      logger.trace(s"Allocating thread local ByteBuffer($size)")
      val b = ByteBuffer.allocate(size)
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
