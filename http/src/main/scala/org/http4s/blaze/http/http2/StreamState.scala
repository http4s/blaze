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

package org.http4s.blaze
package http
package http2

import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.HeadStage

private trait StreamState extends HeadStage[StreamFrame] with WriteInterest {

  /** Whether the `StreamState` is part of the H2 session
    *
    * This is used by client streams to signal that they haven't yet become part of the H2 session
    * since they are 'lazy' until they have emitted the first HEADERS frame, at which point they get
    * assigned a stream id.
    */
  def initialized: Boolean

  /** Stream ID associated with this stream */
  def streamId: Int

  /** The flow window associated with this stream */
  def flowWindow: StreamFlowWindow

  /** Called when the outbound flow window of the session or this stream has had some data acked and
    * we may now be able to make forward progress.
    */
  def outboundFlowWindowChanged(): Unit

  /** Must be called by the [[WriteController]] from within the session executor */
  def performStreamWrite(): collection.Seq[ByteBuffer]

  /** Called by the session when a DATA frame has been received from the remote peer */
  def invokeInboundData(endStream: Boolean, data: ByteBuffer, flowBytes: Int): MaybeError

  /** Called by the session when a HEADERS has been received from the remote peer */
  def invokeInboundHeaders(priority: Priority, endStream: Boolean, headers: Headers): MaybeError

  /** Close the stream, possible due to an error */
  def doCloseWithError(cause: Option[Throwable]): Unit
}
