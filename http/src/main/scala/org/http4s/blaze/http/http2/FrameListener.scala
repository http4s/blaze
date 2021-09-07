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

import java.nio.ByteBuffer
import Http2Settings.Setting

/** Handles the HTTP messages defined in https://tools.ietf.org/html/rfc7540
  *
  * It is expected that implementations will handle validation errors based on the incoming data:
  * the supplier of the data (commonly a [[FrameDecoder]]) is responsible for ensuring that the
  * frames are properly formatted but doesn't concern itself with validating the sanity of a frames
  * content.
  *
  * For example, the [[FrameDecoder]] is responsible for ensuring that the body of a WINDOW_UPDATE
  * is 4 bytes long and the [[FrameListener]] would be expected to signal an error if the window
  * increment was 0 or the update was for an idle stream.
  */
private trait FrameListener {

  /** Determine whether we are in the midst of a sequence of header and header continuation frames
    *
    * Each header block is processed as a discrete unit. Header blocks MUST be transmitted as a
    * contiguous sequence of frames, with no interleaved frames of any other type or from any other
    * stream. https://tools.ietf.org/html/rfc7540#section-4.3
    */
  def inHeaderSequence: Boolean

  /** Called when a DATA frame has been received
    *
    * https://tools.ietf.org/html/rfc7540#section-6.1
    *
    * @param streamId
    *   stream id associated with this data frame. The codec will never set this to 0.
    * @param endStream
    *   is the last inbound frame for this stream
    * @param data
    *   raw data of this message. Does NOT include padding.
    * @param flowSize
    *   bytes counted against the flow window. Includes the data and padding
    */
  def onDataFrame(streamId: Int, endStream: Boolean, data: ByteBuffer, flowSize: Int): Result

  /** Called successful receipt of a HEADERS frame
    *
    * https://tools.ietf.org/html/rfc7540#section-6.2
    *
    * @param streamId
    *   stream id associated with this data frame. The codec will never set this to 0.
    * @param priority
    *   optional priority data associated with this frame.
    * @param endHeaders
    *   This frame contains the entire headers block and is not followed by CONTINUATION frames.
    * @param endStream
    *   The headers block is the last inbound block of the stream. This MAY be followed by
    *   CONTINUATION frames, which are considered part of this headers block.
    * @param data
    *   compressed binary header data
    * @return
    */
  def onHeadersFrame(
      streamId: Int,
      priority: Priority,
      endHeaders: Boolean,
      endStream: Boolean,
      data: ByteBuffer): Result

  /** Called on successful receipt of a CONTINUATION frame
    *
    * https://tools.ietf.org/html/rfc7540#section-6.10
    *
    * @param streamId
    *   stream id associated with this data frame. The codec will never set this to 0.
    * @param endHeaders
    *   this is the last CONTINUATION frame of the headers sequence
    * @param data
    *   compressed binary header data
    */
  def onContinuationFrame(streamId: Int, endHeaders: Boolean, data: ByteBuffer): Result

  /** Called on successful receipt of a PRIORITY frame
    *
    * @param streamId
    *   stream id associated with this priority frame. The codec will never set this to 0.
    * @param priority
    *   priority data
    */
  def onPriorityFrame(streamId: Int, priority: Priority.Dependent): Result

  /** Called on successful receipt of a RST_STREAM frame
    *
    * https://tools.ietf.org/html/rfc7540#section-6.4
    *
    * @param streamId
    *   stream id associated with this RST_STREAM frame. The codec will never set this to 0.
    * @param code
    *   error code detailing the reason for the reset.
    * @return
    */
  def onRstStreamFrame(streamId: Int, code: Long): Result

  /** Called on successful receipt of a SETTINGS frame
    *
    * https://tools.ietf.org/html/rfc7540#section-6.5
    *
    * @param settings
    *   Settings contained in this frame. If this is an ack, `settings` will be `None`.
    */
  def onSettingsFrame(settings: Option[Seq[Setting]]): Result

  /** Called on successful receipt of a PUSH_PROMISE frame
    *
    * https://tools.ietf.org/html/rfc7540#section-6.6
    *
    * @param streamId
    *   stream id associated with this PUSH_PROMISE frame. The codec will never set this to 0.
    * @param promisedId
    *   This stream id must be a stream in the idle state. The codec will never set this to 0.
    * @param end_headers
    *   This is the last frame of this promise block.
    * @param data
    *   compressed binary header data.
    */
  def onPushPromiseFrame(
      streamId: Int,
      promisedId: Int,
      end_headers: Boolean,
      data: ByteBuffer): Result

  /** Called on successful receipt of a PING frame
    *
    * https://tools.ietf.org/html/rfc7540#section-6.7
    *
    * @param ack
    *   if this is an acknowledgment of an outbound PING frame.
    * @param data
    *   opaque data associated with this PING frame.
    */
  def onPingFrame(ack: Boolean, data: Array[Byte]): Result

  /** Called on successful receipt of a GOAWAY frame
    *
    * https://tools.ietf.org/html/rfc7540#section-6.8
    *
    * @param lastStream
    *   last stream id that may have been processed by the peer.
    * @param errorCode
    *   error code describing the reason for the GOAWAY frame.
    * @param debugData
    *   opaque data that may be useful for debugging purposes.
    */
  def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: Array[Byte]): Result

  /** Called on successful receipt of a WINDOW_UPDATE frame
    *
    * https://tools.ietf.org/html/rfc7540#section-6.9
    *
    * @param streamId
    *   stream id to update. Stream id of 0 means this is for the session flow window.
    * @param sizeIncrement
    *   number of bytes to increment the flow window. The codec will never set this to less than 1.
    */
  def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Result
}
