package org.http4s.blaze.http.http20

import java.util.HashMap

import org.http4s.blaze.http.http20.Http2Exception._
import org.http4s.blaze.http.http20.Http2Settings.DefaultSettings
import org.http4s.blaze.http.http20.NodeMsg.Http2Msg
import org.http4s.blaze.pipeline.{LeafBuilder, Command => Cmd}
import org.log4s.getLogger

import scala.collection.mutable

private final class FlowControl(nodeBuilder: Int => LeafBuilder[NodeMsg.Http2Msg],
                          ops: Http2StreamOps,
                          idManager: StreamIdManager,
                          http2Settings: Http2Settings,
                          codec: Http20FrameDecoder with Http20FrameEncoder,
                          headerEncoder: HeaderEncoder) { self =>

  private val logger = getLogger
  private val nodeMap = new HashMap[Int, Http2Stream]()

  // http://httpwg.org/specs/rfc7540.html#InitialWindowSize
  // The connection flow window is not changed by the INITIAL_WINDOW_SIZE setting
  // and must be managed by window update messages
  private val oConnectionWindow = new FlowWindow(DefaultSettings.INITIAL_WINDOW_SIZE)
  private val iConnectionWindow = new FlowWindow(DefaultSettings.INITIAL_WINDOW_SIZE)

  /////////////////////////// Stream management //////////////////////////////////////

  private def getNode(streamId: Int): Option[Http2Stream] = Option(nodeMap.get(streamId))

  def inboundMessage(streamId: Int, msg: Http2Msg): MaybeError = {
    getNode(streamId) match {
      case Some(node) => node.inboundMessage(msg)
      case None =>
        msg match {
          case msg: NodeMsg.HeadersFrame =>
            if (!idManager.validateClientId(streamId)) {
              if (idManager.lastClientId() >= streamId) {
                // Technically, if the stream never existed this is a protocol error but
                // that necessitates keeping track of all the streams that ever existed which
                // could take up a lot of space so we don't.
                // http://httpwg.org/specs/rfc7540.html#rfc.section.5.1.1
                Error(STREAM_CLOSED(streamId, fatal = false))
              }
              else Error(PROTOCOL_ERROR(s"Invalid streamId", streamId, fatal = true))
            }
            else if (nodeCount() >= http2Settings.maxInboundStreams) {
              Error(FLOW_CONTROL_ERROR(s"MAX_CONCURRENT_STREAMS setting exceeded: ${nodeCount()}", fatal = true))
            }
            else {
              val node = makeStream(streamId)
              node.inboundMessage(msg)
            }

          case msg: NodeMsg.DataFrame =>
            if (streamId <= idManager.lastClientId()) {
              // TODO: this flow control handling feels brittle and like it should be consolidated.
              // It can also be found in the Http2Stream type.
              iConnectionWindow.window -= msg.flowBytes

              if (iConnectionWindow() < 0) { // overflowed the connection window
                val msg = s"Message to unopen stream ($streamId) overflowed the connection window (${iConnectionWindow()}"
                logger.info(msg)
                Error(FLOW_CONTROL_ERROR(msg, streamId, fatal = true))
              }
              else {
                // check if connection window needs to be refreshed
                if (iConnectionWindow() < 0.5 * iConnectionWindow.maxWindow) {
                  val buff = codec.mkWindowUpdateFrame(0, iConnectionWindow.maxWindow - iConnectionWindow())
                  iConnectionWindow.window = iConnectionWindow.maxWindow
                  ops.writeBuffers(buff::Nil)
                }
                Error(STREAM_CLOSED(streamId, fatal = false))
              }
            }
            else Error(PROTOCOL_ERROR(s"DATA frame on invalid stream: $streamId", streamId, fatal = true))

        }
    }
  }

  def nodeCount(): Int = nodeMap.size()

  def removeNode(streamId: Int, reason: Throwable, sendDisconnect: Boolean): Option[Http2Stream] = {
    val node = nodeMap.remove(streamId)
    if (node != null && node.isConnected()) {
      node.closeStream(reason)
      if(sendDisconnect) node.inboundCommand(Cmd.Disconnected)
      Some(node)
    }
    else None
  }

  def nodes(): Seq[Http2Stream] =
    mutable.WrappedArray.make(nodeMap.values().toArray())

  def closeAllNodes(): Unit = nodes().foreach { node =>
    removeNode(node.streamId, Cmd.EOF, true)
  }

  ////////////////////////////////////////////////////////////////////////////////////

  def makeStream(streamId: Int): Http2Stream = {
    val stream = new Http2Stream(streamId,
          new FlowWindow(http2Settings.inboundWindow),
            new FlowWindow(http2Settings.outboundInitialWindowSize),
            iConnectionWindow,
            oConnectionWindow,
            http2Settings,
            codec,
            ops,
            headerEncoder)

    nodeBuilder(streamId).base(stream)
    nodeMap.put(streamId, stream)
    stream.inboundCommand(Cmd.Connected)
    stream
  }

  def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): MaybeError = {
    logger.debug(s"Updated window of stream $streamId by $sizeIncrement. ConnectionOutbound: $oConnectionWindow")

    if (streamId > idManager.lastClientId()) { // idle stream: this is a connection PROTOCOL_ERROR
      val msg = s"Received window update frame for idle stream $streamId. Last opened connection: ${idManager.lastClientId()}"
      logger.debug(msg)
      Error(PROTOCOL_ERROR(msg, streamId, fatal = true))
    }
    else if (sizeIncrement <= 0) {
      val fatal = streamId == 0 // this is a connection error if streamId == 0, and they are fatal.
      Error(PROTOCOL_ERROR(s"Invalid WINDOW_UPDATE size: $sizeIncrement", streamId, fatal = fatal))
    }
    else if (streamId == 0) {
      oConnectionWindow.window += sizeIncrement

      if (oConnectionWindow() < 0) {  // overflowed the connection window
        val msg = s"Connection flow control window overflowed with update of $sizeIncrement"
        Error(FLOW_CONTROL_ERROR(msg, streamId, fatal = true))
      }
      else {
        // Allow all the nodes to attempt to write if they want to
        nodes().forall { node =>
          // Each stream will send their own errors through the command pipeline if there is a problem
          node.incrementOutboundWindow(0)
          oConnectionWindow() > 0
        }
        Continue
      }
    }
    else getNode(streamId) match {
      case Some(node) => node.incrementOutboundWindow(sizeIncrement); Continue
      case None       => Continue
    }
  }

  def onInitialWindowSizeChange(newWindow: Int): Unit = {
    val diff = newWindow - http2Settings.outboundInitialWindowSize
    logger.trace(s"Adjusting outbound windows by $diff")
    http2Settings.outboundInitialWindowSize = newWindow

    // We only adjust the stream windows
    // 6.9.2: The connection low-control window can only be changed using WINDOW_UPDATE frames.
    nodes().foreach{ node => node.incrementOutboundWindow(diff) }
  }
}
