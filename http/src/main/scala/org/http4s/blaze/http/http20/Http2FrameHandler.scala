package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets._

import org.http4s.blaze.http.http20.Http2Exception._
import org.http4s.blaze.http.http20.Settings.{DefaultSettings => Default, Setting}
import org.http4s.blaze.pipeline.LeafBuilder
import org.log4s.getLogger

import scala.annotation.tailrec


private class Http2FrameHandler[T](http2Stage: Http2Stage[T],
                  protected val headerDecoder: HeaderDecoder[T],
                                headerEncoder: HeaderEncoder[T],
                                    idManager: StreamIdManager,
                                inboundWindow: Int,
                                 node_builder: () => LeafBuilder[NodeMsg.Http2Msg[T]],
                            maxInboundStreams: Int)
  extends DecodingFrameHandler with Http20FrameDecoder with Http20FrameEncoder { self =>
  override type HeaderType = T

  private[this] val logger = getLogger
  private val http2Settings = new Settings(inboundWindow)

  override def handler: FrameHandler = this

  val flowControl = new FlowControl(http2Stage, inboundWindow, http2Settings,
                                    this, node_builder, headerEncoder)

  override def onCompletePushPromiseFrame(streamId: Int, promisedId: Int, headers: HeaderType): Http2Result =
    Error(PROTOCOL_ERROR("Server received a PUSH_PROMISE frame from a client", streamId))

  override def onCompleteHeadersFrame(streamId: Int,
                                      priority: Option[Priority],
                                      end_stream: Boolean,
                                      headers: HeaderType): Http2Result =
  {
    val msg = NodeMsg.HeadersFrame(priority, end_stream, headers)

    flowControl.getNode(streamId) match {
      case None =>
        if (!idManager.validateClientId(streamId)) Error(PROTOCOL_ERROR(s"Invalid streamId", streamId))
        else if (flowControl.nodeCount() >= maxInboundStreams) {
          Error(FLOW_CONTROL_ERROR(s"MAX_CONCURRENT_STREAMS setting exceeded: ${flowControl.nodeCount()}"))
        }
        else {
          val node = flowControl.makeStream(streamId)
          node.inboundMessage(msg, 0, end_stream)
        }

      case Some(node) => node.inboundMessage(msg, 0, end_stream)
    }
  }

  override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Http2Result = {
    val errStr = UTF_8.decode(debugData).toString()
    if (errorCode != NO_ERROR.code) {
      logger.warn(s"Received GOAWAY(${Http2Exception.get(errorCode.toInt)}}) frame, msg: '$errStr'")
    }

    var liveNodes = false

    flowControl.nodes().foreach { node =>
      if (node.streamId > lastStream) flowControl.removeNode(node.streamId)
      else liveNodes = true
    }

    if (liveNodes) {    // No more streams allowed, but keep the connection going.
      http2Settings.receivedGoAway = true
      Continue
    }
    else {
      http2Stage.shutdownConnection()
      Halt
    }
  }

  override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result = {
    http2Stage.writeBuffers(mkPingFrame(true, data)::Nil)
    Continue
  }

  override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = {
    logger.trace(s"Received settings frames: $settings, ACK: $ack")
    if (ack) Continue    // TODO: ensure client sends acknolegments?
    else {
      val r = processSettings(settings)
      if (r.success) {
        val buff = mkSettingsFrame(true, Nil)
        logger.trace("Writing settings ACK")
        http2Stage.writeBuffers(buff::Nil) // write the ACK settings frame
      }
      r
    }
  }

  @tailrec
  private def processSettings(settings: Seq[Setting]): MaybeError = {
    if (settings.isEmpty) Continue
    else {
      val r = settings.head match {
        case Setting(Settings.HEADER_TABLE_SIZE, v) =>
          headerEncoder.setMaxTableSize(v.toInt)
          Continue

        case Setting(Settings.ENABLE_PUSH, v) =>
          if (v == 0) { http2Settings.push_enable = false; Continue }
          else if (v == 1) {  http2Settings.push_enable = true; Continue }
          else Error(PROTOCOL_ERROR(s"Invalid ENABLE_PUSH setting value: $v"))

        case Setting(Settings.MAX_CONCURRENT_STREAMS, v) =>
          if (v > Integer.MAX_VALUE) {
            Error(PROTOCOL_ERROR(s"To large MAX_CONCURRENT_STREAMS: $v"))
          } else { http2Settings.max_outbound_streams = v.toInt; Continue }

        case Setting(Settings.INITIAL_WINDOW_SIZE, v) =>
          if (v > Integer.MAX_VALUE) Error(FLOW_CONTROL_ERROR(s"Invalid initial window size: $v"))
          else { flowControl.onInitialWindowSizeChange(v.toInt); Continue }

        case Setting(Settings.MAX_FRAME_SIZE, v) =>
          // max of 2^24-1 http/2.0 draft 16 spec
          if (v < Default.MAX_FRAME_SIZE || v > 16777215) Error(PROTOCOL_ERROR(s"Invalid frame size: $v"))
          else { http2Settings.max_frame_size = v.toInt; Continue }


        case Setting(Settings.MAX_HEADER_LIST_SIZE, v) =>
          if (v > Integer.MAX_VALUE) Error(PROTOCOL_ERROR(s"SETTINGS_MAX_HEADER_LIST_SIZE to large: $v"))
          else { http2Settings.max_header_size = v.toInt; Continue }

        case Setting(k, v) =>
          logger.warn(s"Unknown setting ($k, $v)")
          Continue
      }
      if (r.success) processSettings(settings.tail)
      else r
    }
  }

  // For handling unknown stream frames
  override def onExtensionFrame(tpe: Int, streamId: Int, flags: Byte, data: ByteBuffer): Http2Result =
    Error(PROTOCOL_ERROR(s"Unsupported extension frame: type: $tpe, " +
      s"Stream ID: $streamId, flags: $flags, data: $data", streamId))

  override def onRstStreamFrame(streamId: Int, code: Int): Http2Result = {
    val codeName = errorName(code)
    val node = flowControl.removeNode(streamId)
    if (node.isEmpty) {

      if (idManager.lastClientId() < streamId) {
        logger.warn(s"Client attempted to reset idle stream: $streamId, code: $codeName")
        Error(PROTOCOL_ERROR("Attempted to RST idle stream"))
      }
      else {
        logger.info(s"Client attempted to reset closed stream: $streamId, code: $codeName")
        Continue
      }
    }
    else {
      logger.info(s"Stream $streamId reset with code $codeName")
      Continue
    }

  }

  override def onDataFrame(streamId: Int, end_stream: Boolean, data: ByteBuffer, flowSize: Int): Http2Result = {
    flowControl.getNode(streamId) match {
      case Some(node) =>
        val msg = NodeMsg.DataFrame(end_stream, data)
        node.inboundMessage(msg, flowSize, end_stream)

      case None =>
        if (streamId <= idManager.lastClientId()) {
          sendRstStreamFrame(streamId, STREAM_CLOSED())
          Continue
        }  // NOOP, might be old stream
        else Error(PROTOCOL_ERROR(s"DATA frame on invalid stream: $streamId", streamId))
    }
  }

  override def onPriorityFrame(streamId: Int, priority: Priority): Http2Result = {
    // TODO: should we implement some type of priority handling?
    Continue
  }

  override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Http2Result = {
    flowControl.onWindowUpdateFrame(streamId, sizeIncrement)
  }

  private def sendRstStreamFrame(streamId: Int, e: Http2Exception): Unit = {
    http2Stage.writeBuffers(mkRstStreamFrame(streamId, e.code)::Nil)
  }
}
