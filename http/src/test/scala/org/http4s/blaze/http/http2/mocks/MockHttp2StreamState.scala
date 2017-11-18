package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.http2.{Http2Exception, Http2StreamState, StreamFlowWindow}

class MockHttp2StreamState(val streamId: Int, tools: Http2MockTools)
  extends Http2StreamState(tools.writeListener, tools.http2Encoder, tools.serialExecutor) {

  var outboundFlowAcks: Int = 0

  var onStreamFinishedResult: Option[Option[Http2Exception]] = None

  override val flowWindow: StreamFlowWindow = tools.flowControl.newStreamFlowWindow(streamId)

  override def outboundFlowWindowChanged(): Unit = {
    outboundFlowAcks += 1
    super.outboundFlowWindowChanged()
  }

  /** Deals with stream related errors */
  override protected def onStreamFinished(ex: Option[Http2Exception]): Unit = {
    onStreamFinishedResult = Some(ex)
  }

  override protected def maxFrameSize: Int = tools.localSettings.maxFrameSize
}
