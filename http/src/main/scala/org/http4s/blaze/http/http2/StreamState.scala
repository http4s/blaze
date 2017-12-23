package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.HeadStage

private trait StreamState extends HeadStage[StreamMessage] with WriteInterest {

  /** Stream ID associated with this stream */
  def streamId: Int

  /** The flow window associated with this stream */
  def flowWindow: StreamFlowWindow

  /** Called when the outbound flow window of the session or this stream has had some data
    * acked and we may now be able to make forward progress.
    */
  def outboundFlowWindowChanged(): Unit

  /** Must be called by the [[WriteController]] from within the session executor */
  def performStreamWrite(): Seq[ByteBuffer]

  /** Called by the session when a DATA frame has been received from the remote peer */
  def invokeInboundData(endStream: Boolean, data: ByteBuffer, flowBytes: Int): MaybeError

  /** Called by the session when a HEADERS has been received from the remote peer */
  def invokeInboundHeaders(priority: Priority, endStream: Boolean, headers: Seq[(String,String)]): MaybeError

  /** Close the stream, optionally with an error */
  def closeWithError(t: Option[Throwable]): Unit
}
