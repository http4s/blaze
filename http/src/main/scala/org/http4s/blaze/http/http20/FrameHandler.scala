package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

import org.http4s.blaze.http.http20.Settings.Setting

trait FrameHandler {

  def inHeaderSequence(): Boolean

  def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flowSize: Int): Http2Result

  def onHeadersFrame(streamId: Int,
                     priority: Option[Priority],
                  end_headers: Boolean,
                   end_stream: Boolean,
                         data: ByteBuffer): Http2Result

  def onPriorityFrame(streamId: Int, priority: Priority): Http2Result

  def onRstStreamFrame(streamId: Int, code: Int): Http2Result

  def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result

  def onPushPromiseFrame(streamId: Int, promisedId: Int, end_headers: Boolean, data: ByteBuffer): Http2Result

  def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result

  def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Http2Result

  def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Http2Result

  def onContinuationFrame(streamId: Int, endHeaders: Boolean, data: ByteBuffer): Http2Result

  // For handling unknown stream frames
  def onExtensionFrame(tpe: Int, streamId: Int, flags: Byte, data: ByteBuffer): Http2Result

  ///////////// Error Handling //////////////////////////////////
}

