package org.http4s.blaze.http.http2

private class ClientStreamManager(
    session: SessionCore
  ) extends StreamManager(session, StreamIdManager(isClient = true)) {
  override def mkInboundStream(streamId: Int): Option[Http2StreamState] = None
}
