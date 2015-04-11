package org.http4s.blaze.http.http20

import java.nio.ByteBuffer

/** Types that will be sent down to the Nodes of the Http20HubStage */
object NodeMsg {

  sealed trait Http2Msg

  case class DataFrame(isLast: Boolean, data: ByteBuffer) extends Http2Msg

  case class HeadersFrame(priority: Option[Priority],
                         endStream: Boolean,
                           headers: Seq[(String,String)]) extends Http2Msg

  // TODO: how to handle push promise frames?
//  case class PushPromiseFrame[HType](promisedId: Int, headers: HType) extends Http2Msg[HType]

  // For handling unknown stream frames
//  case class ExtensionFrame(tpe: Int, flags: Byte, data: ByteBuffer) extends Http2Msg[Nothing]
}
