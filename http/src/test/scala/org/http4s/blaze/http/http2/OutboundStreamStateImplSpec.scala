/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.mocks.MockStreamFlowWindow
import org.specs2.mutable.Specification

import scala.concurrent.duration.Duration
import scala.util.Failure

class OutboundStreamStateImplSpec extends Specification {
  private class Ctx(connectionState: Connection.State) {
    val streamId = 1

    class MockTools extends mocks.MockTools(isClient = true) {
      override lazy val sessionFlowControl: SessionFlowControl = new MockSessionFlowControl {
        override def newStreamFlowWindow(streamId: Int): StreamFlowWindow = {
          assert(streamId == 1)
          new MockStreamFlowWindow
        }
      }

      override def state: Connection.State = connectionState

      override lazy val streamManager = new StreamManagerImpl(this, None)
    }

    lazy val tools = new MockTools

    def streamManager = tools.streamManager

    lazy val streamState = streamManager.newOutboundStream()
  }

  "OutboundStreamState" should {
    "initialize a flow window and stream id lazily" in {
      val ctx = new Ctx(Connection.Running)
      import ctx._

      streamState.streamId must throwAn[IllegalStateException]
      streamState.writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty))
      streamManager.get(streamState.streamId) must beSome(streamState)
      streamState.streamId must_== 1 // it's hard coded to 1
    }

    "fail write requests if we fail to acquire a stream ID" in {
      val ctx = new Ctx(Connection.Running) {
        override lazy val tools = new MockTools {
          override lazy val idManager = StreamIdManager.create(true, -10) // should be depleted
        }
      }
      import ctx._

      val f = streamState.writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty))
      tools.drainGracePeriod must_== Some(Duration.Inf)
      streamManager.size must_== 0
      f.value must beLike { case Some(Failure(ex: Http2StreamException)) =>
        ex.code must_== Http2Exception.REFUSED_STREAM.code
      }
    }

    "fail write requests if the session is closing" in {
      val ctx = new Ctx(Connection.Closed)
      import ctx._

      val f = streamState.writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty))
      streamManager.size must_== 0
      f.value must beLike { case Some(Failure(ex: Http2StreamException)) =>
        ex.code must_== Http2Exception.REFUSED_STREAM.code
      }
    }
  }
}
