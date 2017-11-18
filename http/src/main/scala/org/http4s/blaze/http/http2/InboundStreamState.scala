package org.http4s.blaze.http.http2

/** Representation of inbound streams */
private final class InboundStreamState(
  session: SessionCore,
  val streamId: Int,
  val flowWindow: StreamFlowWindow
) extends StreamState(session)
