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

import org.http4s.blaze.http.http2.Http2Settings.Setting

// TODO: these may form the basis of what gets written to the WriteListener
@deprecated("HTTP/2 support is unmaintained and will be removed in a future version.", "0.23.18")
sealed abstract class ProtocolFrame private extends Product with Serializable

@deprecated("HTTP/2 support is unmaintained and will be removed in a future version.", "0.23.18")
object ProtocolFrame {
  case class GoAway(lastHandleStream: Int, cause: Http2Exception) extends ProtocolFrame

  case class Ping(isAck: Boolean, data: Array[Byte]) extends ProtocolFrame

  case class Settings(settings: Option[Seq[Setting]]) extends ProtocolFrame

  case object Empty extends ProtocolFrame
}
