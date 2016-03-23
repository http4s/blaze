package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

/** Types that will be sent down to the Nodes of the Http20HubStage */
object NodeMsg {

  sealed trait Http2Msg {
    def endStream: Boolean
  }

  /** Data frame for http2
    *
    * @param endStream if this is the last message of the stream
    * @param data actual stream data
    * @param flowBytes number of bytes that count against the flow control window
    */
  case class DataFrame private(endStream: Boolean, data: ByteBuffer, flowBytes: Int) extends Http2Msg

  object DataFrame {
    @inline
    def apply(endStream: Boolean, data: ByteBuffer): DataFrame =
      DataFrame(endStream, data, data.remaining())

    //
    @inline
    private[http20] def withFlowBytes(endStream: Boolean, data: ByteBuffer, frameBytes: Int) =
      DataFrame(endStream, data, frameBytes)
  }

  /** Headers frame for http2
    *
    * @param priority priority of this stream
    * @param endStream signal if this is the last frame of the stream
    * @param headers attached headers
    */
  case class HeadersFrame(priority: Option[Priority],
                         endStream: Boolean,
                           headers: Seq[(String,String)]) extends Http2Msg

  // TODO: how to handle push promise frames?
//  case class PushPromiseFrame[HType](promisedId: Int, headers: HType) extends Http2Msg[HType]

  // For handling unknown stream frames
//  case class ExtensionFrame(tpe: Int, flags: Byte, data: ByteBuffer) extends Http2Msg[Nothing]
}
