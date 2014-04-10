package org.http4s.blaze.util

import java.nio.ByteBuffer

/**
 * @author Bryce Anderson
 *         Created on 1/28/14
 */
object BufferTools {

  val emptyBuffer: ByteBuffer = ByteBuffer.allocate(0)

  /** Join the two buffers into a single ByteBuffer */
  def concatBuffers(oldbuff: ByteBuffer, newbuff: ByteBuffer): ByteBuffer = {
    if (oldbuff != null && oldbuff.hasRemaining) {
      if (!oldbuff.isReadOnly && oldbuff.capacity() >= oldbuff.limit() + newbuff.remaining()) {
        // Enough room to append to end
        oldbuff.mark()
        oldbuff.position(oldbuff.limit())
        oldbuff.limit(oldbuff.limit() + newbuff.remaining())
        oldbuff.put(newbuff)
        oldbuff.reset()
        oldbuff
      }
      else if (!oldbuff.isReadOnly && oldbuff.capacity() >= oldbuff.remaining() + newbuff.remaining()) {
        // Enough room if we compact oldbuff
        oldbuff.compact().put(newbuff).flip()
        oldbuff
      }
      else {  // Need to make a larger buffer
        val n = ByteBuffer.allocate(oldbuff.remaining() + newbuff.remaining())
        n.put(oldbuff).put(newbuff).flip()
        n
      }
    } else newbuff
  }

  /** Check the array of buffers to ensure they are all empty
    *
    * @param buffers ByteBuffers to check for data
    * @return true if they are empty, false if there is data remaining
    */
  def checkEmpty(buffers: Array[ByteBuffer]): Boolean = {
    def checkEmpty(i: Int): Boolean = {
      if (i < 0) true
      else if (buffers(i).remaining == 0) checkEmpty(i - 1)
      else false
    }
    checkEmpty(buffers.length - 1)
  }

}
