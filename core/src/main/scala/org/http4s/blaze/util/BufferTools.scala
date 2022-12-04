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
import java.nio.charset.{Charset, StandardCharsets}

import scala.annotation.tailrec
import scala.concurrent.Future

object BufferTools {

  /** Cached empty `ByteBuffer` */
  val emptyBuffer: ByteBuffer = allocate(0)

  /** Cached `Future` containing and empty `ByteBuffer` */
  val emptyFutureBuffer: Future[ByteBuffer] = Future.successful(emptyBuffer)

  /** Allocate a new `ByteBuffer` on the heap
    *
    * @param size
    *   size of desired `ByteBuffer`
    */
  def allocate(size: Int): ByteBuffer = ByteBuffer.allocate(size)

  /** Make a copy of the ByteBuffer, depleting the input buffer */
  def copyBuffer(b: ByteBuffer): ByteBuffer = {
    val bb = allocate(b.remaining())
    bb.put(b).flip()
    bb
  }

  /** Merge the `ByteBuffer`s into a single buffer */
  def joinBuffers(buffers: collection.Seq[ByteBuffer]): ByteBuffer =
    buffers match {
      case Seq() => emptyBuffer
      case Seq(b) => b
      case _ =>
        val sz = buffers.foldLeft(0)((sz, o) => sz + o.remaining())
        val b = allocate(sz)
        buffers.foreach(b.put)

        b.flip()
        b
    }

  /** Get the `String` representation of the `ByteBuffer` */
  def bufferToString(buffer: ByteBuffer, charset: Charset = StandardCharsets.UTF_8): String =
    if (!buffer.hasRemaining) ""
    else {
      val arr = new Array[Byte](buffer.remaining())
      buffer.get(arr)
      new String(arr, charset)
    }

  /** Join the two buffers into a single ByteBuffer. This method is guaranteed to return a
    * ByteBuffer, but it may be empty.
    */
  def concatBuffers(oldbuff: ByteBuffer, newbuff: ByteBuffer): ByteBuffer =
    if (oldbuff == null)
      if (newbuff == null) emptyBuffer
      else newbuff
    else if (newbuff == null)
      oldbuff // already established that oldbuff is not `null`
    else if (!oldbuff.hasRemaining) newbuff
    else if (!newbuff.hasRemaining) oldbuff
    else if (!oldbuff.isReadOnly && oldbuff
        .capacity() >= oldbuff.limit() + newbuff.remaining()) {
      // Enough room to append newbuff to the end tof oldbuff
      oldbuff
        .mark()
        .position(oldbuff.limit())
        .limit(oldbuff.limit() + newbuff.remaining())

      oldbuff
        .put(newbuff)
        .reset()

      oldbuff
    } else { // Need to make a larger buffer
      val n = allocate(oldbuff.remaining() + newbuff.remaining())
      n.put(oldbuff)
        .put(newbuff)
        .flip()

      n
    }

  /** Take a slice of bytes from the `ByteBuffer`, consuming the bytes.
    *
    * @param buffer
    *   `ByteBuffer` to slice
    * @param size
    *   number of bytes to slice. Must be less than or equal to the number of bytes remaining in
    *   `buffer`.
    * @return
    *   the resulting view
    */
  def takeSlice(buffer: ByteBuffer, size: Int): ByteBuffer = {
    if (size < 0 || size > buffer.remaining())
      throw new IllegalArgumentException(s"Invalid size: $size. buffer: $buffer")

    val currentLimit = buffer.limit()
    buffer.limit(buffer.position() + size)
    val slice = buffer.slice()

    buffer
      .position(buffer.limit())
      .limit(currentLimit)
    slice
  }

  /** Check the array of buffers to ensure they are all empty
    *
    * @param buffers
    *   `ByteBuffer`s to check for data
    * @return
    *   true if they are empty, false if there is data remaining
    */
  def checkEmpty(buffers: Array[ByteBuffer]): Boolean = {
    @tailrec
    def checkEmpty(i: Int): Boolean =
      if (i < 0) true
      else if (!buffers(i).hasRemaining()) checkEmpty(i - 1)
      else false
    checkEmpty(buffers.length - 1)
  }

  /** Replaces any empty buffers except for the last one with the `emptyBuffer` to allow GC of
    * depleted ByteBuffers and returns the index of the first non-empty ByteBuffer, or the last
    * index, whichever comes first.
    */
  def dropEmpty(buffers: Array[ByteBuffer]): Int = {
    val max = buffers.length - 1
    var first = 0
    while (first < max && !buffers(first).hasRemaining()) {
      buffers(first) = emptyBuffer
      first += 1
    }
    first
  }

  /** Check the array of buffers to ensure they are all empty
    *
    * @param buffers
    *   `ByteBuffer`s to check for data
    * @return
    *   true if they are empty, false if there is data remaining
    */
  def checkEmpty(buffers: Iterable[ByteBuffer]): Boolean =
    !buffers.iterator.exists(_.hasRemaining)

  /** Make a String from the collection of ByteBuffers */
  def mkString(buffers: Seq[ByteBuffer], charset: Charset = StandardCharsets.UTF_8): String = {
    val b = joinBuffers(buffers)
    charset.decode(b).toString()
  }

  /** Copies as much data from the input buffers as possible without modifying positions of the
    * input buffers
    *
    * @param buffers
    *   collection of buffers to copy. This may be an empty array and the array may contain `null`
    *   elements. The positions, marks, and marks of the input buffers will not be modified.
    * @param out
    *   `ByteBuffer` that the data will be copied into. This must not be `null`
    * @return
    *   Number of bytes copied.
    */
  private[blaze] def copyBuffers(buffers: Array[ByteBuffer], out: ByteBuffer): Int = {
    val start = out.position()

    @tailrec
    def go(i: Int): Unit =
      if (!out.hasRemaining || i >= buffers.length) ()
      else if (buffers(i) == null || !buffers(i).hasRemaining) go(i + 1)
      else {
        val buffer = buffers(i)
        // Need to store the state and ensure we don't overflow the output buffer
        val position = buffer.position()
        val limit = buffer.limit()
        buffer.limit(math.min(limit, position + out.remaining()))
        out.put(buffer)

        // Reset the buffers position and limit
        buffer.limit(limit)
        buffer.position(position)
        go(i + 1)
      }

    go(0)

    out.position() - start
  }

  /** Forward the positions of the collection of `ByteBuffer`s
    *
    * @param buffers
    *   `ByteBuffers` to modify. The positions will be incremented from the first in the collection
    *   to the last.
    * @param size
    *   Number of bytes to fast-forward the arrays
    * @return
    *   whether there was enough bytes in the collection of buffers or if the size overran the
    *   available data.
    */
  private[blaze] def fastForwardBuffers(buffers: Array[ByteBuffer], size: Int): Boolean = {
    require(size >= 0)
    @tailrec
    def go(i: Int, remaining: Int): Int =
      if (remaining == 0 || i >= buffers.length) remaining
      else {
        val buffer = buffers(i)
        if (buffer == null || !buffer.hasRemaining) go(i + 1, remaining)
        else {
          val toForward = math.min(remaining, buffer.remaining())
          buffer.position(buffer.position() + toForward)
          go(i + 1, remaining - toForward)
        }
      }

    go(0, size) == 0
  }

  /** Check if all the `ByteBuffer`s in the array are either direct, empty, or null */
  private[blaze] def areDirectOrEmpty(buffers: Array[ByteBuffer]): Boolean = {
    @tailrec
    def go(i: Int): Boolean =
      if (i >= buffers.length) true
      else {
        val buffer = buffers(i)
        if (buffer == null || buffer.isDirect || !buffer.hasRemaining) go(i + 1)
        else false
      }

    go(0)
  }

  /** Make a String which contains the hex representation of the buffers data
    *
    * The passed buffer is not mutated, even temporarily.
    *
    * @note
    *   this is not intended to be a high performance method and should only be used for debugging
    *   purposes.
    */
  private[blaze] def hexString(buffer: ByteBuffer, limit: Int = Int.MaxValue): String = {
    val sb = new StringBuilder(buffer.toString)
    sb.append(" 0x")
    val readOnly = buffer.asReadOnlyBuffer() // makes a read-only copy
    @tailrec
    def go(i: Int): Unit =
      if (i < limit && readOnly.hasRemaining) {
        sb.append("%02X".format(readOnly.get()))
        go(i + 1)
      }
    go(0)

    sb.result()
  }
}
