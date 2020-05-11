/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
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
  * @param endStream if this is the last message of the stream
  * @param data actual stream data. The `ByteBuffer` indexes may be modified by the receiver.
  *             The `ByteBuffer` indexes are considered owned by this DataFrame, but its
  *             data must not be modified.
  */
case class DataFrame(endStream: Boolean, data: ByteBuffer) extends StreamFrame {
  def flowBytes: Int = data.remaining()
}

/** Headers frame for http2
  *
  * @param priority priority of this stream
  * @param endStream signal if this is the last frame of the stream
  * @param headers attached headers
  */
case class HeadersFrame(priority: Priority, endStream: Boolean, headers: Headers)
    extends StreamFrame {
  override def flowBytes: Int = 0
}
