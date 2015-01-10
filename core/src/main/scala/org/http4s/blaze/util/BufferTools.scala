package org.http4s.blaze.util

import java.nio.ByteBuffer
import java.nio.charset.{StandardCharsets, Charset}

import scala.annotation.tailrec

object BufferTools {

  val emptyBuffer: ByteBuffer = allocate(0)

  /** Allocate a fresh `ByteBuffer`
    *
    * @param size size of desired `ByteBuffer`
    */
  def allocate(size: Int): ByteBuffer = ByteBuffer.allocate(size)

  /** Make a copy of the ByteBuffer, zeroing the input buffer */
  def copyBuffer(b: ByteBuffer): ByteBuffer = {
    val bb = allocate(b.remaining())
    bb.put(b).flip()
    bb
  }

  /** Merge the `ByteBuffer`s into a single buffer */
  def joinBuffers(buffers: Seq[ByteBuffer]): ByteBuffer = buffers match {
    case Seq()  => emptyBuffer
    case Seq(b) => b
    case _      =>
      val sz = buffers.foldLeft(0)((sz, o) => sz + o.remaining())
      val b = allocate(sz)
      buffers.foreach(b.put)

      b.flip()
      b
  }

  /** Get the `String` representation of the `ByteBuffer` */
  def bufferToString(buffer: ByteBuffer): String = {
    if (buffer.hasRemaining) {
      val arr = new Array[Byte](buffer.remaining())
      buffer.get(arr)
      new String(arr)
    }
    else ""
  }

  /** Join the two buffers into a single ByteBuffer. This method is
    * guaranteed to return a ByteBuffer, but it may be empty. */
  def concatBuffers(oldbuff: ByteBuffer, newbuff: ByteBuffer): ByteBuffer = {
    if (oldbuff == null && newbuff == null) emptyBuffer
    else if (oldbuff != null && oldbuff.hasRemaining) {
      if (!oldbuff.isReadOnly && oldbuff.capacity() >= oldbuff.limit() + newbuff.remaining()) {
        // Enough room to append to end
        oldbuff.mark()
               .position(oldbuff.limit())
               .limit(oldbuff.limit() + newbuff.remaining())

        oldbuff.put(newbuff)
               .reset()

        oldbuff
      }
      else if (!oldbuff.isReadOnly && oldbuff.capacity() >= oldbuff.remaining() + newbuff.remaining()) {
        // Enough room if we compact oldbuff
        oldbuff.compact()
               .put(newbuff)
               .flip()

        oldbuff
      }
      else {  // Need to make a larger buffer
        val n = allocate(oldbuff.remaining() + newbuff.remaining())
        n.put(oldbuff)
         .put(newbuff)
         .flip()

        n
      }
    }
    else newbuff
  }

  /** Check the array of buffers to ensure they are all empty
    *
    * @param buffers `ByteBuffer`s to check for data
    * @return true if they are empty, false if there is data remaining
    */
  def checkEmpty(buffers: Array[ByteBuffer]): Boolean = {
    @tailrec
    def checkEmpty(i: Int): Boolean = {
      if (i < 0) true
      else if (!buffers(i).hasRemaining()) checkEmpty(i - 1)
      else false
    }
    checkEmpty(buffers.length - 1)
  }

  /** Replaces any empty buffers except for the last one with the `emptyBuffer`
    * to allow GC of depleted ByteBuffers and returns the index of the first
    * non-empty ByteBuffer, or the last index, whichever comes first. */
  def dropEmpty(buffers: Array[ByteBuffer]): Int = {
    val max = buffers.length - 1
    var first = 0
    while(first < max && !buffers(first).hasRemaining()) {
      buffers(first) = emptyBuffer
      first += 1
    }
    first
  }

  /** Check the array of buffers to ensure they are all empty
    *
    * @param buffers `ByteBuffer`s to check for data
    * @return true if they are empty, false if there is data remaining
    */
  def checkEmpty(buffers: TraversableOnce[ByteBuffer]): Boolean =
    !buffers.exists(_.hasRemaining)

  /** Make a String from the collection of ByteBuffers */
  def mkString(buffers: Seq[ByteBuffer],charset: Charset = StandardCharsets.UTF_8): String = {
    val b = joinBuffers(buffers)
    charset.decode(b).toString()
  }
}
