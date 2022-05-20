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

import org.http4s.blaze.http.http2.FlowStrategy.Increment

/** `FlowStrategy` advises a session when to send window updates
  *
  * A `FlowStrategy` will be shared among many sessions.
  */
trait FlowStrategy {

  /** Decide if the session window needs to send a WINDOW_UPDATE frame
    *
    * @note
    *   This must not mutate the [[SessionFlowControl]] in any way.
    * @note
    *   This verison should only be used in situations where the stream associated with the data
    *   does not exist. For example, it may have already closed and sent a RST frame.
    *
    * @param session
    *   the session [[SessionFlowControl]]
    * @return
    *   number of bytes to update the session flow window with.
    */
  def checkSession(session: SessionFlowControl): Int

  /** Decide if the stream and/or the session need a WINDOW_UPDATE frame
    *
    * @note
    *   This must not mutate the [[SessionFlowControl]] or the [[StreamFlowWindow]] in any way.
    *
    * @param stream
    *   the stream [[StreamFlowWindow]]
    * @return
    *   the number of bytes to update the session and stream flow window with.
    */
  def checkStream(stream: StreamFlowWindow): Increment
}

object FlowStrategy {
  // Make the object private to restrict construction of
  // `Increment`s to the `makeIncrement` method
  private object Increment {
    private[this] val Empty = new Increment(0, 0)

    def make(session: Int, stream: Int): Increment =
      if (session == 0 && stream == 0) Empty
      else new Increment(session, stream)
  }

  /** Representation of the flow window increments to send to the remote peer */
  final case class Increment private (session: Int, stream: Int)

  // Cached version for avoiding allocations in the common case

  /** Construct an `Increment`, using a cached version if possible */
  def increment(session: Int, stream: Int): Increment =
    Increment.make(session, stream)
}
