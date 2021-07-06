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
import org.http4s.blaze.testkit.BlazeTestSuite

import scala.concurrent.duration.Duration
import scala.util.Failure

class OutboundStreamStateImplSuite extends BlazeTestSuite {

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

  test("An OutboundStreamState should initialize a flow window and stream id lazily") {
    val ctx = new Ctx(Connection.Running)
    import ctx._

    intercept[IllegalStateException](streamState.streamId)
    streamState.writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty))
    assertEquals(streamManager.get(streamState.streamId), Some(streamState))
    assertEquals(streamState.streamId, 1) // it's hard coded to 1
  }

  test("An OutboundStreamState should fail write requests if we fail to acquire a stream ID") {
    val ctx = new Ctx(Connection.Running) {
      override lazy val tools = new MockTools {
        override lazy val idManager = StreamIdManager.create(true, -10) // should be depleted
      }
    }
    import ctx._

    val f = streamState.writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty))

    assertEquals(tools.drainGracePeriod, Some(Duration.Inf))
    assertEquals(streamManager.size, 0)
    f.value match {
      case Some(Failure(ex: Http2StreamException)) =>
        assertEquals(ex.code, Http2Exception.REFUSED_STREAM.code)

      case _ =>
        fail("Unexpected writeRequest result found")
    }
  }

  test("An OutboundStreamState should fail write requests if the session is closing") {
    val ctx = new Ctx(Connection.Closed)
    import ctx._

    val f = streamState.writeRequest(HeadersFrame(Priority.NoPriority, true, Seq.empty))

    assertEquals(streamManager.size, 0)
    f.value match {
      case Some(Failure(ex: Http2StreamException)) =>
        assertEquals(ex.code, Http2Exception.REFUSED_STREAM.code)

      case _ =>
        fail("Unexpected writeRequest result found")
    }
  }
}
