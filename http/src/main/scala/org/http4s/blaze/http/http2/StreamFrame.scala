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

/** Types that will be sent down to the Nodes of the Http2 session */
sealed trait StreamFrame {
  def endStream: Boolean
  def flowBytes: Int
}

/** Data frame for http2
  *
  * @param endStream
  *   if this is the last message of the stream
  * @param data
  *   actual stream data. The `ByteBuffer` indexes may be modified by the receiver. The `ByteBuffer`
  *   indexes are considered owned by this DataFrame, but its data must not be modified.
  */
case class DataFrame(endStream: Boolean, data: ByteBuffer) extends StreamFrame {
  def flowBytes: Int = data.remaining()
}

/** Headers frame for http2
  *
  * @param priority
  *   priority of this stream
  * @param endStream
  *   signal if this is the last frame of the stream
  * @param headers
  *   attached headers
  */
case class HeadersFrame(priority: Priority, endStream: Boolean, headers: Headers)
    extends StreamFrame {
  override def flowBytes: Int = 0
}
