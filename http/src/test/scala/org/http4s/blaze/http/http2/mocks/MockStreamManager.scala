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

package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.Headers
import org.http4s.blaze.http.http2.{MaybeError, Result, _}

import scala.collection.mutable
import scala.concurrent.Future

private[http2] class MockStreamManager() extends StreamManager {
  override def size: Int = 0

  val finishedStreams = new mutable.Queue[StreamState]

  override def streamClosed(stream: StreamState): Boolean = {
    finishedStreams += stream
    true
  }

  override def newInboundStream(streamId: Int) = ???

  override def newOutboundStream() = ???

  override def initialFlowWindowChange(delta: Int): MaybeError = ???

  override def flowWindowUpdate(streamId: Int, sizeIncrement: Int): MaybeError = ???

  override def handlePushPromise(streamId: Int, promisedId: Int, headers: Headers): Result = ???

  override def get(streamId: Int): Option[StreamState] = None

  override def drain(lastHandledOutboundStream: Int, reason: Http2SessionException): Future[Unit] =
    ???

  override def rstStream(cause: Http2StreamException): MaybeError = Continue

  override def forceClose(cause: Option[Throwable]): Unit = ???

  override def isEmpty: Boolean = ???
}
