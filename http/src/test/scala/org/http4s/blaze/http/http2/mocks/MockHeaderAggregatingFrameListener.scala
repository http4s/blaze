package org.http4s.blaze.http.http2.mocks

import java.nio.ByteBuffer

import org.http4s.blaze.http._
import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.http.http2._

private[http2] class MockHeaderAggregatingFrameListener
    extends HeaderAggregatingFrameListener(
      Http2Settings.default,
      new HeaderDecoder(20 * 1024, true, 4096)) {

  override def onCompletePushPromiseFrame(
      streamId: Int,
      promisedId: Int,
      headers: Headers): Result = ???
  override def onCompleteHeadersFrame(
      streamId: Int,
      priority: Priority,
      end_stream: Boolean,
      headers: Headers): Result = ???
  override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: Array[Byte]): Result = ???
  override def onPingFrame(ack: Boolean, data: Array[Byte]): Result = ???
  override def onSettingsFrame(settings: Option[Seq[Setting]]): Result = ???

  // For handling unknown stream frames
  override def onRstStreamFrame(streamId: Int, code: Long): Result = ???
  override def onDataFrame(
      streamId: Int,
      isLast: Boolean,
      data: ByteBuffer,
      flowSize: Int): Result = ???
  override def onPriorityFrame(streamId: Int, priority: Priority.Dependent): Result = ???
  override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Result = ???
}
