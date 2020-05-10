/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2

private final class InboundStreamStateImpl(
    session: SessionCore,
    val streamId: Int,
    val flowWindow: StreamFlowWindow
) extends StreamStateImpl(session)
    with InboundStreamState {
  override def name: String = s"InboundStreamState($streamId)"

  // Inbound streams are always initialized
  override def initialized: Boolean = true
}
