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
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http._
import org.http4s.blaze.http.http2.Http2Exception._
import org.http4s.blaze.http.http2.Http2Settings.Setting

/** Receives frames from the `Http2FrameDecoder`
  *
  * Concurrency is not controlled by this type; it is expected that thread safety will be managed by
  * the [[ConnectionImpl]].
  */
private class SessionFrameListener(
    session: SessionCore,
    isClient: Boolean,
    headerDecoder: HeaderDecoder)
    extends HeaderAggregatingFrameListener(session.localSettings, headerDecoder) {
  // Concrete methods ////////////////////////////////////////////////////////////////////

  override def onCompleteHeadersFrame(
      streamId: Int,
      priority: Priority,
      endStream: Boolean,
      headers: Headers): Result =
    session.streamManager.get(streamId) match {
      case Some(stream) =>
        stream.invokeInboundHeaders(priority, endStream, headers)

      case None =>
        session.streamManager.newInboundStream(streamId) match {
          case Left(ex) => Error(ex)
          case Right(is) =>
            is.invokeInboundHeaders(priority, endStream, headers)
        }
    }

  // See https://tools.ietf.org/html/rfc7540#section-6.6 and section-8.2 for the list of rules
  override def onCompletePushPromiseFrame(
      streamId: Int,
      promisedId: Int,
      headers: Headers): Result =
    if (!isClient)
      // A client cannot push. Thus, servers MUST treat the receipt of a
      // PUSH_PROMISE frame as a connection error of type PROTOCOL_ERROR.
      // https://tools.ietf.org/html/rfc7540#section-8.2
      Error(PROTOCOL_ERROR.goaway(s"Server received PUSH_PROMISE frame for stream $streamId"))
    else if (!session.localSettings.pushEnabled)
      // PUSH_PROMISE MUST NOT be sent if the SETTINGS_ENABLE_PUSH setting of
      // the peer endpoint is set to 0.  An endpoint that has set this setting
      // and has received acknowledgement MUST treat the receipt of a
      // PUSH_PROMISE frame as a connection error (Section 5.4.1) of type PROTOCOL_ERROR.
      Error(PROTOCOL_ERROR.goaway("Received PUSH_PROMISE frame then they are disallowed"))
    else
      session.streamManager.handlePushPromise(streamId, promisedId, headers)

  override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flow: Int): Result =
    session.streamManager.get(streamId) match {
      case Some(stream) =>
        // the stream will deal with updating the flow windows
        stream.invokeInboundData(isLast, data, flow)

      case None =>
        if (!session.sessionFlowControl.sessionInboundObserved(flow)) {
          val msg = s"data frame for inactive stream (id $streamId) overflowed " +
            s"session flow window. Size: $flow."
          Error(FLOW_CONTROL_ERROR.goaway(msg))
        } else if (session.idManager.isIdleId(streamId))
          Error(PROTOCOL_ERROR.goaway(s"DATA on uninitialized stream ($streamId)"))
        else
          // There is an intrinsic race here: the server may have closed the stream
          // (and sent a RST) and removed it from the active streams. However, the
          // peer may not be aware of this yet and may still be sending data. That is
          // Legal.
          // However, if the *peer* sends a RST frame for this stream, we also remove
          // it from the active streams. This means there is a legal and illegal way
          // to receive a DATA frame after the stream has been closed and unless we
          // want to keep some historical state for streams, we can't tell. So, we just
          // be kind and send a RST frame in both cases.
          Error(STREAM_CLOSED.rst(streamId))
    }

  // TODO: what would priority handling look like?
  override def onPriorityFrame(streamId: Int, priority: Priority.Dependent): Result =
    Continue

  // https://tools.ietf.org/html/rfc7540#section-6.4
  override def onRstStreamFrame(streamId: Int, code: Long): Result = {
    val ex = Http2Exception.errorGenerator(code).rst(streamId)
    session.streamManager.rstStream(ex)
  }

  override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Result =
    session.streamManager.flowWindowUpdate(streamId, sizeIncrement)

  override def onPingFrame(ack: Boolean, data: Array[Byte]): Result = {
    // TODO: need to prioritize ping acks
    if (!ack) session.writeController.write(session.http2Encoder.pingAck(data))
    else session.pingManager.pingAckReceived(data)
    Continue
  }

  override def onSettingsFrame(settings: Option[Seq[Setting]]): Result =
    settings match {
      // We don't consider the uncertainty between us sending a SETTINGS frame and its ack
      // but that's OK since we never update settings after the initial handshake.
      case None => Continue // ack
      case Some(settings) =>
        val remoteSettings = session.remoteSettings
        // These two settings require some action if they change
        val initialInitialWindowSize = remoteSettings.initialWindowSize
        val initialHeaderTableSize = remoteSettings.headerTableSize

        val result = remoteSettings.updateSettings(settings) match {
          case Some(ex) => Error(ex) // problem with settings
          case None =>
            if (remoteSettings.headerTableSize != initialHeaderTableSize)
              session.http2Encoder.setMaxTableSize(remoteSettings.headerTableSize)

            val diff = remoteSettings.initialWindowSize - initialInitialWindowSize
            if (diff == 0) Continue
            else
              // https://tools.ietf.org/html/rfc7540#section-6.9.2
              // a receiver MUST adjust the size of all stream flow-control windows that
              // it maintains by the difference between the new value and the old value.
              session.streamManager.initialFlowWindowChange(diff)
        }

        if (result == Continue)
          // ack the SETTINGS frame on success, otherwise we are tearing
          // down the connection anyway so no need
          session.writeController.write(FrameSerializer.mkSettingsAckFrame())

        result
    }

  override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: Array[Byte]): Result = {
    val message = new String(debugData, StandardCharsets.UTF_8)
    session.invokeGoAway(lastStream, Http2SessionException(errorCode, message))
    Continue
  }
}
