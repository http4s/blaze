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

private[http2] object StreamIdManager {

  /** Create a new [[StreamIdManager]] */
  def apply(isClient: Boolean): StreamIdManager = create(isClient, 0)

  // Exposed internally for testing overflow behavior
  private[http2] def create(isClient: Boolean, startingId: Int): StreamIdManager = {
    require(startingId % 2 == 0)

    val nextInbound = startingId + (if (isClient) 2 else 1)
    val nextOutbound = startingId + (if (isClient) 1 else 2)
    new StreamIdManager(isClient, nextInbound, nextOutbound)
  }
}

/** Tool for tracking stream ids */
private final class StreamIdManager private (
    isClient: Boolean,
    private var nextInbound: Int,
    private var nextOutbound: Int) {

  /** Get the last inbound stream to be observed, or 0 if no streams have been processed */
  def lastInboundStream: Int = math.max(0, nextInbound - 2)

  /** Get the last outbound stream to be observed, or 0 if no streams have been initiated */
  def lastOutboundStream: Int = math.max(0, nextOutbound - 2)

  /** Determine if the stream id is an inbound stream id */
  def isInboundId(id: Int): Boolean = {
    require(id >= 0)
    // For the client, inbound streams will be even. All non-session stream ids are > 0
    id > 0 && (id % 2 == 0) == isClient
  }

  /** Determine if the id corresponds to an idle stream */
  def isIdleId(id: Int): Boolean =
    isIdleInboundId(id) || isIdleOutboundId(id)

  /** Determine if the stream id is an outbound stream id */
  def isOutboundId(id: Int): Boolean = !isInboundId(id) && id > 0

  /** Determine if the stream id is both an inbound id and is idle */
  def isIdleInboundId(id: Int): Boolean =
    isInboundId(id) && id >= nextInbound && nextInbound > 0 // make sure we didn't overflow

  /** Determine if the stream id is both an outbound id and is idle */
  def isIdleOutboundId(id: Int): Boolean =
    isOutboundId(id) && id >= nextOutbound && nextOutbound > 0 // make sure we didn't overflow

  /** Determine if the client ID is valid based on the stream history */
  def validateNewInboundId(id: Int): Boolean =
    if (isInboundId(id) && id >= nextInbound) {
      nextInbound = id + 2
      true
    } else false

  /** Mark the stream id non-idle, and any idle inbound streams with lower ids
    *
    * If the stream id is an inbound stream id and is idle then the specified it and all inbound
    * id's preceding it are marked as non-idle.
    *
    * @param id
    *   stream id to observe
    * @return
    *   `true` if observed, `false` otherwise.
    */
  def observeInboundId(id: Int): Boolean =
    if (!isIdleInboundId(id)) false
    else {
      nextInbound = id + 2
      true
    }

  /** Acquire the next outbound stream id
    *
    * @return
    *   the next streamId wrapped in `Some` if it exists, `None` otherwise.
    */
  def takeOutboundId(): Option[Int] =
    // Returns `None` if the stream id overflows, which is when a signed Int overflows
    if (unusedOutboundStreams) {
      val result = Some(nextOutbound)
      nextOutbound += 2
      result
    } else None

  def unusedOutboundStreams: Boolean = nextOutbound > 0
}
