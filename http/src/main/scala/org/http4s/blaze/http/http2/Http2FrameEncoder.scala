package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.util.BufferTools

import scala.collection.immutable.VectorBuilder

/** A more humane interface for writing HTTP messages.
  */
final class Http2FrameEncoder(
    peerSettings: Http2Settings,
    headerEncoder: HeaderEncoder) {

  // Just a shortcut
  private[this] def maxFrameSize: Int = peerSettings.maxFrameSize

  // Encoder side
  def setMaxTableSize(size: Int): Unit =
    headerEncoder.setMaxTableSize(size)

  def sessionWindowUpdate(size: Int): ByteBuffer =
    streamWindowUpdate(0, size)

  def streamWindowUpdate(streamId: Int, size: Int): ByteBuffer =
    Http2FrameSerializer.mkWindowUpdateFrame(streamId, size)

  def pingFrame(data: Array[Byte]): ByteBuffer =
    Http2FrameSerializer.mkPingFrame(false, data)

  def pingAck(data: Array[Byte]): ByteBuffer =
    Http2FrameSerializer.mkPingFrame(true, data)

  def dataFrame(streamId: Int, endStream: Boolean, data: ByteBuffer): Seq[ByteBuffer] = {
    val limit = maxFrameSize
    if (data.remaining <= limit) Http2FrameSerializer.mkDataFrame(streamId, endStream, padding = 0, data)
    else { // need to fragment
      val acc = new VectorBuilder[ByteBuffer]
      while(data.hasRemaining) {
        val thisData = BufferTools.takeSlice(data, math.min(data.remaining, limit))
        val eos = endStream && !data.hasRemaining
        acc ++= Http2FrameSerializer.mkDataFrame(streamId, eos, padding = 0, thisData)
      }
      acc.result()
    }
  }

  def headerFrame(streamId: Int,
                  priority: Option[Priority],
                  endStream: Boolean,
                  headers: Seq[(String, String)]): Seq[ByteBuffer] = {
    val rawHeaders = headerEncoder.encodeHeaders(headers)

    val limit = maxFrameSize
    val headersPrioritySize = if (priority.isDefined) 5 else 0 // priority(4) + weight(1), padding = 0

    if (rawHeaders.remaining() + headersPrioritySize <= limit) {
      Http2FrameSerializer.mkHeaderFrame(streamId, priority, endHeaders = true, endStream, padding = 0, rawHeaders)
    } else {
      // need to fragment
      val acc = new VectorBuilder[ByteBuffer]

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
