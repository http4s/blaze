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

package org.http4s.blaze
package http
package http2

import java.nio.ByteBuffer
import org.http4s.blaze.util.BufferTools
import scala.collection.mutable.ArrayBuffer

/** A more humane interface for writing HTTP messages. */
private final class FrameEncoder(remoteSettings: Http2Settings, headerEncoder: HeaderEncoder) {
  // Just a shortcut
  private[this] def maxFrameSize: Int = remoteSettings.maxFrameSize

  /** Set the max table size of the header encoder */
  def setMaxTableSize(size: Int): Unit =
    headerEncoder.maxTableSize(size)

  /** Generate a window update frame for the session flow window */
  def sessionWindowUpdate(size: Int): ByteBuffer =
    streamWindowUpdate(0, size)

  /** Generate a window update frame for the specified stream flow window */
  def streamWindowUpdate(streamId: Int, size: Int): ByteBuffer =
    FrameSerializer.mkWindowUpdateFrame(streamId, size)

  /** Generate a ping frame */
  def pingFrame(data: Array[Byte]): ByteBuffer =
    FrameSerializer.mkPingFrame(false, data)

  /** Generate a ping ack frame with the specified data */
  def pingAck(data: Array[Byte]): ByteBuffer =
    FrameSerializer.mkPingFrame(true, data)

  /** Generate a RST frame with the specified stream id and error code */
  def rstFrame(streamId: Int, errorCode: Long): ByteBuffer =
    FrameSerializer.mkRstStreamFrame(streamId, errorCode)

  /** Generate stream data frame(s) for the specified data
    *
    * If the data exceeds the peers MAX_FRAME_SIZE setting, it is fragmented into a series of
    * frames.
    */
  def dataFrame(streamId: Int, endStream: Boolean, data: ByteBuffer): collection.Seq[ByteBuffer] = {
    val limit = maxFrameSize
    if (data.remaining <= limit)
      FrameSerializer.mkDataFrame(streamId, endStream, padding = 0, data)
    else { // need to fragment
      val acc = new ArrayBuffer[ByteBuffer]
      while (data.hasRemaining) {
        val thisData =
          BufferTools.takeSlice(data, math.min(data.remaining, limit))
        val eos = endStream && !data.hasRemaining
        acc ++= FrameSerializer.mkDataFrame(streamId, eos, padding = 0, thisData)
      }
      acc
    }
  }

  /** Generate stream header frames from the provided header sequence
    *
    * If the compressed representation of the headers exceeds the MAX_FRAME_SIZE setting of the
    * peer, it will be broken into a HEADERS frame and a series of CONTINUATION frames.
    */
  def headerFrame(
      streamId: Int,
      priority: Priority,
      endStream: Boolean,
      headers: Headers
  ): collection.Seq[ByteBuffer] = {
    val rawHeaders = headerEncoder.encodeHeaders(headers)

    val limit = maxFrameSize
    val headersPrioritySize =
      if (priority.isDefined) 5 else 0 // priority(4) + weight(1), padding = 0

    if (rawHeaders.remaining() + headersPrioritySize <= limit)
      FrameSerializer.mkHeaderFrame(
        streamId,
        priority,
        endHeaders = true,
        endStream,
        padding = 0,
        rawHeaders)
    else {
      // need to fragment
      val acc = new ArrayBuffer[ByteBuffer]

      val headersBuf =
        BufferTools.takeSlice(rawHeaders, limit - headersPrioritySize)
      acc ++= FrameSerializer.mkHeaderFrame(
        streamId,
        priority,
        endHeaders = false,
        endStream,
        padding = 0,
        headersBuf)

      while (rawHeaders.hasRemaining) {
        val size = math.min(limit, rawHeaders.remaining)
        val continueBuf = BufferTools.takeSlice(rawHeaders, size)
        val endHeaders = !rawHeaders.hasRemaining
        acc ++= FrameSerializer.mkContinuationFrame(streamId, endHeaders, continueBuf)
      }
      acc
    }
  }
}
