package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.http2.{Http2Exception, SessionCore, StreamFlowWindow, StreamState}

private[http2] class MockStreamState(
    session: SessionCore,
    val streamId: Int) extends StreamState(session) {

  var outboundFlowAcks: Int = 0

  var onStreamFinishedResult: Option[Option[Http2Exception]] = None

  override val flowWindow: StreamFlowWindow = session.sessionFlowControl.newStreamFlowWindow(streamId)

  override def outboundFlowWindowChanged(): Unit = {
    outboundFlowAcks += 1
    super.outboundFlowWindowChanged()
  }
}
