package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http._
import org.http4s.blaze.http.http2.Http2Exception._
import org.http4s.blaze.http.http2.Http2Settings.Setting

import org.log4s.getLogger

import scala.concurrent.duration.Duration

/** Receives frames from the `Http2FrameDecoder`
  *
  * Concurrency is not controlled by this type; it is expected that thread safety
  * will be managed by the [[Http2ConnectionImpl]].
  */
private class SessionFrameListener(
    headerDecoder: HeaderDecoder,
    session: SessionCore)
  extends HeaderAggregatingFrameListener(session.localSettings, headerDecoder) {

  private[this] val logger = getLogger

  // Just an alias
  private[this] def idManager = session.streamManager.idManager

  // Concrete methods ////////////////////////////////////////////////////////////////////

  override def onCompleteHeadersFrame(streamId: Int, priority: Priority, endStream: Boolean, headers: Headers): Http2Result = {
    val streamManager = session.streamManager
    streamManager.get(streamId) match {
      case Some(stream) =>
        stream.invokeInboundHeaders(priority, endStream, headers)

      case None =>
        if (streamId == 0) {
          Error(PROTOCOL_ERROR.goaway(s"Illegal stream ID for headers frame: 0"))
        } else if (idManager.observeInboundId(streamId)) {
          if (streamManager.size >= session.localSettings.maxConcurrentStreams) {
            // 5.1.2 Stream Concurrency
            //
            // Endpoints MUST NOT exceed the limit set by their peer.  An endpoint
            // that receives a HEADERS frame that causes its advertised concurrent
            // stream limit to be exceeded MUST treat this as a stream error
            // (Section 5.4.2) of type PROTOCOL_ERROR or REFUSED_STREAM.  The choice
            // of error code determines whether the endpoint wishes to enable
            // automatic retry (see Section 8.1.4) for details).
            Error(REFUSED_STREAM.rst(streamId))
          }
          else {
            streamManager.newInboundStream(streamId) match {
              case Some(head) => head.invokeInboundHeaders(priority, endStream, headers)
              case None =>  // stream rejected
                Error(REFUSED_STREAM.rst(streamId))
            }
          }
        } else if (idManager.isIdleOutboundId(streamId)) {
          Error(PROTOCOL_ERROR.goaway(s"Received HEADERS frame for idle outbound stream id $streamId"))
        } else {
          Error(PROTOCOL_ERROR.goaway(s"Received HEADERS frame for non-idle inbound stream id $streamId"))
        }
    }
  }

  // See https://tools.ietf.org/html/rfc7540#section-6.6 and section-8.2 for the list of rules
  override def onCompletePushPromiseFrame(streamId: Int, promisedId: Int, headers: Headers): Http2Result = {
    if (!idManager.isClient) {
      Error(PROTOCOL_ERROR.goaway(s"Server received PUSH_PROMISE frame for stream $streamId"))
    } else if (!session.localSettings.pushEnabled) {
      Error(PROTOCOL_ERROR.goaway("Received PUSH_PROMISE frame then they are disallowed"))
    } else if (idManager.isIdleOutboundId(streamId)) {
      Error(PROTOCOL_ERROR.goaway(s"Received PUSH_PROMISE for associated to an idle stream ($streamId)"))
    } else if (!idManager.isInboundId(promisedId)) {
      Error(PROTOCOL_ERROR.goaway(s"Received PUSH_PROMISE frame with illegal stream id: $promisedId"))
    } else if (!idManager.observeInboundId(promisedId)) {
      Error(PROTOCOL_ERROR.goaway("Received PUSH_PROMISE frame on non-idle stream"))
    } else {
      session.streamManager.handlePushPromise(streamId, promisedId, headers)
    }
  }

  override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flow: Int): Http2Result = {
    session.streamManager.get(streamId) match {
      case Some(stream) =>
        // the stream will deal with updating the flow windows
        stream.invokeInboundData(isLast, data, flow)

      case None =>
        if (!session.sessionFlowControl.sessionInboundObserved(flow)) {
          val msg = s"data frame for inactive stream (id $streamId) overflowed session flow window. Size: $flow."
          Error(FLOW_CONTROL_ERROR.goaway(msg))
        } else if (idManager.isIdleId(streamId)) {
          Error(PROTOCOL_ERROR.goaway(s"DATA on uninitialized stream ($streamId)"))
        } else {
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
    }
  }

  // TODO: what would priority handling look like?
  override def onPriorityFrame(streamId: Int, priority: Priority.Dependent): Http2Result =
    Continue

  // https://tools.ietf.org/html/rfc7540#section-6.4
  override def onRstStreamFrame(streamId: Int, code: Long): Http2Result = {
    if (idManager.isIdleId(streamId)) {
      Error(PROTOCOL_ERROR.goaway(s"RST_STREAM for idle stream id $streamId"))
    } else {
      // We remove it from the active streams first so that we don't send our own RST_STREAM
      // frame as a response. https://tools.ietf.org/html/rfc7540#section-5.4.2
      val ex = Http2Exception.errorGenerator(code).rst(streamId)
      session.streamManager.closeStream(streamId, Some(ex))

      // We *must not* send a Http2StreamException or else it will be written
      // to the peer potentially resulting a cycle of RST frames.
      Continue
    }
  }

  override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Http2Result = {
    if (idManager.isIdleId(streamId)) {
      Error(PROTOCOL_ERROR.goaway(s"WINDOW_UPDATE on uninitialized stream ($streamId)"))
    } else if (sizeIncrement <= 0) {
      // Illegal update size. https://tools.ietf.org/html/rfc7540#section-6.9
      if (streamId == 0) {
        Error(PROTOCOL_ERROR.goaway(s"Session WINDOW_UPDATE of invalid size: $sizeIncrement"))
      } else {
        val err = FLOW_CONTROL_ERROR.rst(streamId, s"Session WINDOW_UPDATE of invalid size: $sizeIncrement")
        // We don't remove the stream: it is still 'active' and `closeWithError` will trigger sending
        // the RST_STREAM and removing the exception from the active streams collection
        session.streamManager.closeStream(streamId, Some(err))
        Error(err)
      }
    } else {
      session.streamManager.windowUpdate(streamId, sizeIncrement)
    }
  }

  override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result = {
    // TODO: need to prioritize ping acks
    if (!ack) session.writeController.write(session.http2Encoder.pingAck(data))
    else session.pingManager.pingAckReceived(data)
    Continue
  }

  override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = {
    // We don't consider the uncertainty between us sending a SETTINGS frame and its ack
    // but that's OK since we never update settings after the initial handshake.
    if (ack) Continue
    else {
      val remoteSettings = session.remoteSettings
      // These two settings require some action if they change
      val initialInitialWindowSize = remoteSettings.initialWindowSize
      val initialHeaderTableSize = remoteSettings.headerTableSize

      val result = MutableHttp2Settings.updateSettings(remoteSettings, settings) match {
        case Some(ex) => Error(ex) // problem with settings
        case None =>
          if (remoteSettings.headerTableSize != initialHeaderTableSize) {
            session.http2Encoder.setMaxTableSize(remoteSettings.headerTableSize)
          }

          val diff = remoteSettings.initialWindowSize - initialInitialWindowSize
          if (diff == 0) Continue
          else {
            // https://tools.ietf.org/html/rfc7540#section-6.9.2
            // a receiver MUST adjust the size of all stream flow-control windows that
            // it maintains by the difference between the new value and the old value.
            session.streamManager.initialFlowWindowChange(diff)
          }
      }

      if (result == Continue) {
        // ack the SETTINGS frame on success, otherwise we are tearing down the connection anyway so no need
        session.writeController.write(Http2FrameSerializer.mkSettingsAckFrame())
      }

      result
    }
  }

  override def onGoAwayFrame(lastStream: Int,errorCode: Long,debugData: Array[Byte]): Http2Result = {
    val message = new String(debugData, StandardCharsets.UTF_8)
    logger.debug {
      val errorName = Http2Exception.errorName(errorCode)
      val errorCodeStr = s"0x${java.lang.Long.toHexString(errorCode)}: $errorName"
      s"Received GOAWAY($errorCodeStr, '$message'). Last processed stream: $lastStream"
    }

    session.invokeGoaway(lastStream, message)
    Continue
  }
}