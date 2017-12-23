package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.http2.OutboundStreamState

private[http2] class MockOutboundStreamState extends MockStreamState with OutboundStreamState {
  override def streamId: Int = ???
}
