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

package org.http4s.blaze.core.websocket

/** Raised by the WebSocket frame aggregator when the total payload of a
  * fragmented message would exceed the configured limit. Mapped to a
  * `Close(1009)` ("Message Too Big") frame by [[Http4sWSStage]].
  */
private[http4s] final class WebSocketMessageTooLargeException
    extends Exception("Aggregated WebSocket message exceeds configured size limit")
