package org.http4s.blaze.http.http2

private final class InboundStreamStateImpl(
    session: SessionCore,
    val streamId: Int,
    val flowWindow: StreamFlowWindow
) extends StreamStateImpl(session)
    with InboundStreamState {
  override def name: String = s"InboundStreamState($streamId)"
}
