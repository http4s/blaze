package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

/** Types that will be sent down to the Nodes of the Http2 session */

sealed trait StreamMessage {
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
case class DataFrame(endStream: Boolean, data: ByteBuffer) extends StreamMessage {
  def flowBytes = data.remaining()
}

/** Headers frame for http2
  *
  * @param priority priority of this stream
  * @param endStream signal if this is the last frame of the stream
  * @param headers attached headers
  */
case class HeadersFrame(priority: Priority,
                       endStream: Boolean,
                         headers: Seq[(String,String)]) extends StreamMessage {
  override def flowBytes: Int = 0
}

// TODO: how to handle push promise frames?
//  case class PushPromiseFrame[HType](promisedId: Int, headers: HType) extends Http2Msg[HType]

// For handling unknown stream frames
//  case class ExtensionFrame(tpe: Int, flags: Byte, data: ByteBuffer) extends Http2Msg[Nothing]

