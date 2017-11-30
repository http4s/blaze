package org.http4s.blaze.http.http2.mocks

import java.nio.ByteBuffer

import org.http4s.blaze.http._
import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.http.http2._

private[http2] class MockHeaderAggregatingFrameListener extends HeaderAggregatingFrameListener(
  Http2Settings.default, new HeaderDecoder(20*1024, true, 4096)) {

  override def onCompletePushPromiseFrame(streamId: Int, promisedId: Int, headers: Headers): Http2Result = ???
  override def onCompleteHeadersFrame(streamId: Int, priority: Priority, end_stream: Boolean, headers: Headers): Http2Result = ???
  override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: Array[Byte]): Http2Result = ???
  override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result = ???
  override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = ???

  // For handling unknown stream frames
  override def onRstStreamFrame(streamId: Int, code: Long): Http2Result = ???
  override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flowSize: Int): Http2Result = ???
  override def onPriorityFrame(streamId: Int, priority: Priority.Dependent): Http2Result = ???
  override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Http2Result = ???
}