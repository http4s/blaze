/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2

import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

class DefaultFlowStrategySpec extends Specification with Mockito {
  def newStrategy(): DefaultFlowStrategy = {
    val settings = MutableHttp2Settings.default()
    settings.initialWindowSize = 2
    new DefaultFlowStrategy(settings)
  }

  def session(window: Int, unconsumed: Int): SessionFlowControl = {
    val sessionFlowControl = mock[SessionFlowControl]
    sessionFlowControl.sessionInboundWindow.returns(window)
    sessionFlowControl.sessionUnconsumedBytes.returns(unconsumed)
    sessionFlowControl
  }

  "DefaultFlowStrategy" should {
    "not update session if the window hasn't dropped to half" in {
      val sessionFlowControl = session(2, 2)
      newStrategy().checkSession(sessionFlowControl) must_== 0
    }

    "update session if the window has dropped to half" in {
      val sessionFlowControl = session(1, 0)
      newStrategy().checkSession(sessionFlowControl) must_== 1
    }

    "not update session if the window has dropped to half but hasn't been consumed" in {
      val sessionFlowControl = session(1, 1)
      newStrategy().checkSession(sessionFlowControl) must_== 0
    }

    "not update stream if the window hasn't dropped to half" in {
      val sessionFlowControl = session(2, 2)

      val streamFlowWindow = mock[StreamFlowWindow]
      streamFlowWindow.sessionFlowControl.returns(sessionFlowControl)
      streamFlowWindow.streamInboundWindow.returns(2)
      streamFlowWindow.streamUnconsumedBytes.returns(0)

      newStrategy().checkStream(streamFlowWindow) must_== FlowStrategy.increment(0, 0)
    }

    "not update stream if the window has dropped to half but not the bytes consumed" in {
      val sessionFlowControl = session(2, 2)

      val streamFlowWindow = mock[StreamFlowWindow]
      streamFlowWindow.sessionFlowControl.returns(sessionFlowControl)
      streamFlowWindow.streamInboundWindow.returns(1)
      streamFlowWindow.streamUnconsumedBytes.returns(1)

      newStrategy().checkStream(streamFlowWindow) must_== FlowStrategy.increment(0, 0)
    }

    "not update stream if the window has dropped to half but not the bytes consumed" in {
      val sessionFlowControl = session(2, 2)

      val streamFlowWindow = mock[StreamFlowWindow]
      streamFlowWindow.sessionFlowControl.returns(sessionFlowControl)
      streamFlowWindow.streamInboundWindow.returns(1)
      streamFlowWindow.streamUnconsumedBytes.returns(1)

      newStrategy().checkStream(streamFlowWindow) must_== FlowStrategy.increment(0, 0)
    }

    "update stream if the window has dropped to half" in {
      val sessionFlowControl = session(2, 2)

      val streamFlowWindow = mock[StreamFlowWindow]
      streamFlowWindow.sessionFlowControl.returns(sessionFlowControl)
      streamFlowWindow.streamInboundWindow.returns(1)
      streamFlowWindow.streamUnconsumedBytes.returns(0)

      newStrategy().checkStream(streamFlowWindow) must_== FlowStrategy.increment(0, 1)
    }

    "update session when considering a stream update" in {
      val sessionFlowControl = session(1, 0)

      val streamFlowWindow = mock[StreamFlowWindow]
      streamFlowWindow.sessionFlowControl.returns(sessionFlowControl)
      streamFlowWindow.streamInboundWindow.returns(2)
      streamFlowWindow.streamUnconsumedBytes.returns(0)

      newStrategy().checkStream(streamFlowWindow) must_== FlowStrategy.increment(1, 0)
    }
  }
}
