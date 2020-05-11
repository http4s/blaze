/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.http2.{SessionCore, SessionFlowControlImpl, StreamFlowWindow}

/** Extends the [[SessionFlowControlImpl]] class but makes a couple critical methods no-ops */
private[http2] class ObservingSessionFlowControl(
    session: SessionCore
) extends SessionFlowControlImpl(
      session = session,
      flowStrategy = null /* only used on two overridden methods */ ) {
  override protected def onSessonBytesConsumed(consumed: Int): Unit = ()
  override protected def onStreamBytesConsumed(stream: StreamFlowWindow, consumed: Int): Unit = ()
}
