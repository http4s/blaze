package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import org.http4s.blaze.util.BufferTools
import scala.collection.mutable.ArrayBuffer

/** A more humane interface for writing HTTP messages. */
final class Http2FrameEncoder(
    peerSettings: Http2Settings,
    headerEncoder: HeaderEncoder) {

  // Just a shortcut
  private[this] def maxFrameSize: Int = peerSettings.maxFrameSize

  /** Set the max table size of the header encoder */
  def setMaxTableSize(size: Int): Unit =
    headerEncoder.maxTableSize(size)

  /** Generate a window update frame for the session flow window */
  def sessionWindowUpdate(size: Int): ByteBuffer =
    streamWindowUpdate(0, size)

  /** Generate a window update frame for the specified stream flow window */
  def streamWindowUpdate(streamId: Int, size: Int): ByteBuffer =
    Http2FrameSerializer.mkWindowUpdateFrame(streamId, size)

  /** Generate a ping frame */
  def pingFrame(data: Array[Byte]): ByteBuffer =
    Http2FrameSerializer.mkPingFrame(false, data)

  /** Generate a ping ack frame with the specified data */
  def pingAck(data: Array[Byte]): ByteBuffer =
    Http2FrameSerializer.mkPingFrame(true, data)

  /** Generate stream data frame(s) for the specified data
    *
    * If the data exceeds the peers MAX_FRAME_SIZE setting, it is fragmented
    * into a series of frames.
    */
  def dataFrame(streamId: Int, endStream: Boolean, data: ByteBuffer): Seq[ByteBuffer] = {
    val limit = maxFrameSize
    if (data.remaining <= limit) Http2FrameSerializer.mkDataFrame(streamId, endStream, padding = 0, data)
    else { // need to fragment
      val acc = new ArrayBuffer[ByteBuffer]
      while(data.hasRemaining) {
        val thisData = BufferTools.takeSlice(data, math.min(data.remaining, limit))
        val eos = endStream && !data.hasRemaining
        acc ++= Http2FrameSerializer.mkDataFrame(streamId, eos, padding = 0, thisData)
      }
      acc.result()
    }
  }

  /** Generate stream header frames from the provided header sequence
    *
    * If the compressed representation of the headers exceeds the MAX_FRAME_SIZE
    * setting of the peer, it will be broken into a HEADERS frame and a series of
    * CONTINUATION frames.
    */
  def headerFrame(
    streamId: Int,
    priority: Priority,
    endStream: Boolean,
    headers: Seq[(String, String)]
  ): Seq[ByteBuffer] = {
    val rawHeaders = headerEncoder.encodeHeaders(headers)

    val limit = maxFrameSize
    val headersPrioritySize = if (priority.isDefined) 5 else 0 // priority(4) + weight(1), padding = 0

    if (rawHeaders.remaining() + headersPrioritySize <= limit) {
      Http2FrameSerializer.mkHeaderFrame(streamId, priority, endHeaders = true, endStream, padding = 0, rawHeaders)
    } else {
      // need to fragment
      val acc = new ArrayBuffer[ByteBuffer]

      val headersBuf = BufferTools.takeSlice(rawHeaders, limit - headersPrioritySize)
      acc ++= Http2FrameSerializer.mkHeaderFrame(streamId, priority, endHeaders = false, endStream, padding = 0, headersBuf)

      while(rawHeaders.hasRemaining) {
        val size = math.min(limit, rawHeaders.remaining)
        val continueBuf = BufferTools.takeSlice(rawHeaders, size)
        val endHeaders = !rawHeaders.hasRemaining
        acc ++= Http2FrameSerializer.mkContinuationFrame(streamId, endHeaders, continueBuf)
      }
      acc.result()
    }
  }
}
