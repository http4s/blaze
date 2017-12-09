package org.http4s.blaze.http.http2

// Place holder
private trait StreamManager {

  def size: Int

  def streamFinished(stream: StreamState, cause: Option[Http2Exception]): Unit

  def registerOutboundStream(state: OutboundStreamState): Option[Int]
}
