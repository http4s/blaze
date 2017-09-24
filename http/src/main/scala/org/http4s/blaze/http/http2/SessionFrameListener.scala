package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http._
import org.http4s.blaze.http.http2.Http2Exception._
import org.log4s.getLogger

import scala.collection.mutable.Map

/** Receives frames from the `Http2FrameDecoder`
  *
  * Concurrency is not controlled by this type; it is expected that thread safety
  * will be managed by the [[Http2ConnectionImpl]].
  */
private abstract class SessionFrameListener[StreamState <: Http2StreamState](
    mySettings: Http2Settings,
    headerDecoder: HeaderDecoder,
    activeStreams: Map[Int, StreamState],
    sessionFlowControl: SessionFlowControl,
    idManager: StreamIdManager)
  extends HeaderAggregatingFrameListener(mySettings, headerDecoder) {

  private[this] val logger = getLogger

  /** Optionally create and initialize a new inbound stream
    *
    * `None` signals that the stream is to be refused with a RST(REFUSED_STREAM) reply.
    *
    * @param streamId streamId associated with the new stream
    */
  protected def newInboundStream(streamId: Int): Option[StreamState]

  /** A Ping frame has been received, either new or an ping ACK */
  override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result

  /** Handle a valid and complete PUSH_PROMISE frame */
  protected def handlePushPromise(streamId: Int, promisedId: Int, headers: Headers): Http2Result

  // Concrete methods ////////////////////////////////////////////////////////////////////

  override def onCompleteHeadersFrame(streamId: Int, priority: Priority, endStream: Boolean, headers: Headers): Http2Result = {
    activeStreams.get(streamId) match {
      case Some(stream) =>
        stream.invokeInboundHeaders(priority, endStream, headers)

      case None =>
        if (streamId == 0) {
          Error(PROTOCOL_ERROR.goaway(s"Illegal stream ID for headers frame: 0"))
        } else if (idManager.observeInboundId(streamId)) {
          if (activeStreams.size >= mySettings.maxConcurrentStreams) {
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
            newInboundStream(streamId) match {
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
    } else if (!mySettings.pushEnabled) {
      Error(PROTOCOL_ERROR.goaway("Received PUSH_PROMISE frame then they are disallowed"))
    } else if (idManager.isIdleOutboundId(streamId)) {
      Error(PROTOCOL_ERROR.goaway(s"Received PUSH_PROMISE for associated to an idle stream ($streamId)"))
    } else if (!idManager.isInboundId(promisedId)) {
      Error(PROTOCOL_ERROR.goaway(s"Received PUSH_PROMISE frame with illegal stream id: $promisedId"))
    } else if (!idManager.observeInboundId(promisedId)) {
      Error(PROTOCOL_ERROR.goaway("Received PUSH_PROMISE frame on non-idle stream"))
    } else {
      handlePushPromise(streamId, promisedId, headers)
    }
  }

  override def onDataFrame(streamId: Int, isLast: Boolean, data: ByteBuffer, flow: Int): Http2Result = {
    activeStreams.get(streamId) match {
      case Some(stream) =>
        // the stream will deal with updating the flow windows
        stream.invokeInboundData(isLast, data, flow)

      case None =>
        if (!sessionFlowControl.sessionInboundObserved(flow)) {
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
      activeStreams
        .remove(streamId)
        .foreach(_.closeWithError(Some(ex)))

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
        activeStreams.get(streamId).foreach(_.closeWithError(Some(err)))
        Error(err)
      }
    } else if (streamId == 0) {
      val result = sessionFlowControl.sessionOutboundAcked(sizeIncrement)
      logger.debug(s"Session flow update: $sizeIncrement. Result: $result")
      if (result.success) {
        // TODO: do we need to wake all the open streams in every case? Maybe just when we go from 0 to > 0?
        activeStreams.values.foreach(_.outboundFlowWindowChanged())
      }
      result
    } else {
      activeStreams.get(streamId) match {
        case None =>
          logger.debug(s"Stream WINDOW_UPDATE($sizeIncrement) for closed stream $streamId")
          Continue // nop

        case Some(stream) =>
          val result = stream.flowWindow.streamOutboundAcked(sizeIncrement)
          logger.debug(s"Stream(${stream.streamId}) WINDOW_UPDATE($sizeIncrement). Result: $result")

          if (result.success) {
            stream.outboundFlowWindowChanged()
          }

          result
      }
    }
  }
}