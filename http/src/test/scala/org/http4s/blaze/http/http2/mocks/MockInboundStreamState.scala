package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.http2.InboundStreamState

private[http2] class MockInboundStreamState(val streamId: Int) extends MockStreamState with InboundStreamState
