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

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.US_ASCII

import org.http4s.blaze.http.http2.Http2Exception._
import org.http4s.blaze.http.http2.Http2Settings.DefaultSettings
import org.http4s.blaze.util.BufferTools
import com.twitter.hpack.{Decoder, HeaderListener}

import scala.collection.immutable.VectorBuilder
import scala.util.control.NonFatal

/** Decoder of HEADERS frame payloads into a collection of key-value pairs using a HPACK decoder.
  *
  * @param maxHeaderListSize
  *   maximum allowed size of the header block.
  *
  * "The value is based on the uncompressed size of header fields, including the length of the name
  * and value in octets plus an overhead of 32 octets for each header field."
  * https://tools.ietf.org/html/rfc7540#section-6.5.2
  * @param maxTableSize
  *   maximum compression table to maintain
  */
private final class HeaderDecoder(
    maxHeaderListSize: Int,
    discardOverflowHeaders: Boolean,
    val maxTableSize: Int
) {
  require(maxTableSize >= DefaultSettings.HEADER_TABLE_SIZE)

  private[this] val acc = new VectorBuilder[(String, String)]
  private[this] var leftovers: ByteBuffer = null
  private[this] var headerBlockSize = 0
  private[this] var sawEndHeaders = false

  private[this] val decoder = new Decoder(maxHeaderListSize, maxTableSize)
  private[this] val listener = new HeaderListener {
    override def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit = {
      headerBlockSize += 32 + name.length + value.length
      if (!discardOverflowHeaders || !headerListSizeOverflow) {
        acc += new String(name, US_ASCII) -> new String(value, US_ASCII)
        ()
      }
    }
  }

  /** Set the HEADER_TABLE_SIZE parameter */
  def setMaxHeaderTableSize(max: Int): Unit = decoder.setMaxHeaderTableSize(max)

  /** Returns the header collection and clears the builder */
  def finish(): Seq[(String, String)] = {
    if (!sawEndHeaders)
      throw new IllegalStateException(
        "Should only be called after decoding the a terminating header fragment")

    leftovers = null
    headerBlockSize = 0
    sawEndHeaders = false
    val result = acc.result()
    acc.clear()
    result
  }

  def currentHeaderBlockSize: Int = headerBlockSize

  def headerListSizeOverflow: Boolean =
    currentHeaderBlockSize > maxHeaderListSize

  /** Decode the headers into the internal header accumulator */
  def decode(buffer: ByteBuffer, streamId: Int, endHeaders: Boolean): MaybeError =
    doDecode(buffer, streamId, endHeaders, listener)

  private[this] def doDecode(
      buffer: ByteBuffer,
      streamId: Int,
      endHeaders: Boolean,
      listener: HeaderListener): MaybeError = {
    if (sawEndHeaders)
      throw new IllegalStateException("called doDecode() after receiving an endHeaders flag")

    try {
      sawEndHeaders = endHeaders
      val buff = BufferTools.concatBuffers(leftovers, buffer)
      decoder.decode(new ByteBufferInputStream(buff), listener)

      if (!buff.hasRemaining()) leftovers = null
      else if (buff ne buffer)
        leftovers = buff // we made a copy with concatBuffers
      else { // buff == input buffer. Need to copy the input buffer so we are not sharing it
        val b = BufferTools.allocate(buff.remaining())
        b.put(buff).flip()
        leftovers = b
      }

      if (endHeaders)
        decoder.endHeaderBlock()

      Continue
    } catch {
      case NonFatal(_) =>
        Error(COMPRESSION_ERROR.goaway(s"Compression error on stream $streamId"))
    }
  }
}
