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

import org.http4s.blaze.http.Headers
import org.http4s.blaze.util.BufferTools
import Http2Exception.PROTOCOL_ERROR

/** A [[FrameListener]] that decodes raw HEADERS, PUSH_PROMISE, and CONTINUATION frames from
  * ByteBuffer packets to a complete collections of headers.
  *
  * If the size of the raw header blcok exceeds the MAX_HEADER_LIST_SIZE parameter we send a GOAWAY
  * frame. This can legally be handled with a 431 response, but the headers must be processed to
  * keep the header decompressor in a valid state.
  * https://tools.ietf.org/html/rfc7540#section-10.5.1
  *
  * @note
  *   This class is not 'thread safe' and should be treated accordingly.
  */
private abstract class HeaderAggregatingFrameListener(
    localSettings: Http2Settings,
    headerDecoder: HeaderDecoder)
    extends FrameListener {
  private[this] sealed trait PartialFrame {
    def streamId: Int
    var buffer: ByteBuffer
  }

  private[this] case class PHeaders(
      streamId: Int,
      priority: Priority,
      endStream: Boolean,
      var buffer: ByteBuffer)
      extends PartialFrame

  private[this] case class PPromise(streamId: Int, promisedId: Int, var buffer: ByteBuffer)
      extends PartialFrame

  private[this] var hInfo: PartialFrame = null

  // /////////////////////////////////////////////////////////////////////////

  /** Called on the successful receipt of a complete HEADERS block
    *
    * @param streamId
    *   stream id of the HEADERS block. The codec will never pass 0.
    * @param priority
    *   optional priority information associated with this HEADERS block.
    * @param endStream
    *   this is the last inbound frame for this stream.
    * @param headers
    *   decompressed headers.
    */
  def onCompleteHeadersFrame(
      streamId: Int,
      priority: Priority,
      endStream: Boolean,
      headers: Headers
  ): Result

  /** Called on the successful receipt of a complete PUSH_PROMISE block
    *
    * @param streamId
    *   stream id of the associated stream. The codec will never pass 0.
    * @param promisedId
    *   promised stream id. This must be a valid, idle stream id. The codec will never pass 0.
    * @param headers
    *   decompressed headers.
    */
  def onCompletePushPromiseFrame(streamId: Int, promisedId: Int, headers: Headers): Result

  // //////////////////////////////////////////////////////////////////////////

  final def setMaxHeaderTableSize(maxSize: Int): Unit =
    headerDecoder.setMaxHeaderTableSize(maxSize)

  final override def inHeaderSequence: Boolean = hInfo != null

  final override def onHeadersFrame(
      streamId: Int,
      priority: Priority,
      endHeaders: Boolean,
      endStream: Boolean,
      buffer: ByteBuffer
  ): Result =
    if (inHeaderSequence)
      Error(
        PROTOCOL_ERROR.goaway(s"Received HEADERS frame while in in headers sequence. Stream id " +
          FrameDecoder.hexStr(streamId)))
    else if (buffer.remaining > localSettings.maxHeaderListSize)
      headerSizeError(buffer.remaining, streamId)
    else if (endHeaders) {
      val r = headerDecoder.decode(buffer, streamId, true)
      if (!r.success) r
      else {
        val hs = headerDecoder.finish()
        onCompleteHeadersFrame(streamId, priority, endStream, hs)
      }
    } else {
      hInfo = PHeaders(streamId, priority, endStream, buffer)
      Continue
    }

  final override def onPushPromiseFrame(
      streamId: Int,
      promisedId: Int,
      endHeaders: Boolean,
      buffer: ByteBuffer
  ): Result =
    if (localSettings.maxHeaderListSize < buffer.remaining)
      headerSizeError(buffer.remaining, streamId)
    else if (endHeaders) {
      val r = headerDecoder.decode(buffer, streamId, true)
      if (!r.success) r
      else {
        val hs = headerDecoder.finish()
        onCompletePushPromiseFrame(streamId, promisedId, hs)
      }
    } else {
      hInfo = PPromise(streamId, promisedId, buffer)
      Continue
    }

  final override def onContinuationFrame(
      streamId: Int,
      endHeaders: Boolean,
      buffer: ByteBuffer
  ): Result =
    if (hInfo.streamId != streamId) {
      val msg = s"Invalid CONTINUATION frame: stream Id's don't match. " +
        s"Expected ${hInfo.streamId}, received $streamId"
      Error(PROTOCOL_ERROR.goaway(msg))
    } else {
      val totalSize = buffer.remaining + hInfo.buffer.remaining
      if (localSettings.maxHeaderListSize < totalSize)
        headerSizeError(totalSize, streamId)
      else {
        val newBuffer = BufferTools.concatBuffers(hInfo.buffer, buffer)

        if (endHeaders) {
          val r = headerDecoder.decode(newBuffer, streamId, true)
          if (!r.success) r
          else {
            val hs = headerDecoder.finish()
            val i = hInfo // drop the reference before doing the stateful action
            hInfo = null

            i match {
              case PHeaders(sid, pri, es, _) =>
                onCompleteHeadersFrame(sid, pri, es, hs)
              case PPromise(sid, pro, _) =>
                onCompletePushPromiseFrame(sid, pro, hs)
            }
          }
        } else {
          hInfo.buffer = newBuffer
          Continue
        }
      }
    }

  private[this] def headerSizeError(size: Int, streamId: Int): Error = {
    val msg = s"Stream(${FrameDecoder.hexStr(streamId)}) sent too large of " +
      s"a header block. Received: $size. Limit: ${localSettings.maxHeaderListSize}"
    Error(PROTOCOL_ERROR.goaway(msg))
  }
}
