package org.http4s.blaze.http.http2.mocks

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.http.http2.{FrameListener, Priority, Result}

private[http2] class MockFrameListener(inHeaders: Boolean) extends FrameListener {
  override def inHeaderSequence: Boolean = inHeaders
  override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: Array[Byte]): Result = ???
  override def onPingFrame(ack: Boolean, data: Array[Byte]): Result = ???
  override def onPushPromiseFrame(
      streamId: Int,
      promisedId: Int,
      end_headers: Boolean,
      data: ByteBuffer): Result = ???

  // For handling unknown stream frames
  override def onHeadersFrame(
      streamId: Int,
      priority: Priority,
      end_headers: Boolean,
      end_stream: Boolean,
      buffer: ByteBuffer): Result = ???
  override def onSettingsFrame(settings: Option[Seq[Setting]]): Result = ???
  override def onRstStreamFrame(streamId: Int, code: Long): Result = ???
  override def onPriorityFrame(streamId: Int, priority: Priority.Dependent): Result = ???
  override def onContinuationFrame(streamId: Int, endHeaders: Boolean, data: ByteBuffer): Result =
    ???
  override def onDataFrame(
      streamId: Int,
      isLast: Boolean,
      data: ByteBuffer,
      flowSize: Int): Result = ???
  override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Result = ???
}
