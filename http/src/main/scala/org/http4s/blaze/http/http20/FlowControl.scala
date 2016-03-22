package org.http4s.blaze.http.http20

import java.util.HashMap

import org.http4s.blaze.http.http20.Http2Exception._
import org.http4s.blaze.pipeline.{LeafBuilder, Command => Cmd}
import org.log4s.getLogger

import scala.collection.mutable

private class FlowControl(nodeBuilder: Int => LeafBuilder[NodeMsg.Http2Msg],
                          http2Stage: Http2StreamOps,
                          idManager: StreamIdManager,
                          http2Settings: Http2Settings,
                          codec: Http20FrameDecoder with Http20FrameEncoder,
                          headerEncoder: HeaderEncoder) { self =>

  private val logger = getLogger
  private val nodeMap = new HashMap[Int, Http2Stream]()

  private val oConnectionWindow = new FlowWindow(http2Settings.outboundInitialWindowSize)
  private val iConnectionWindow = new FlowWindow(http2Settings.inboundWindow)

  /////////////////////////// Stream management //////////////////////////////////////

  def getNode(streamId: Int): Option[Http2Stream] = Option(nodeMap.get(streamId))

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
            http2Stage,
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
      Error(PROTOCOL_ERROR(msg, streamId, true))
    }
    else if (sizeIncrement <= 0) {
      if (streamId == 0) Error(PROTOCOL_ERROR(s"Invalid WINDOW_UPDATE size: $sizeIncrement", fatal = true))
      else Error(PROTOCOL_ERROR(streamId, fatal = false))
    }
    else if (streamId == 0) {
      oConnectionWindow.window += sizeIncrement

      if (oConnectionWindow() < 0) {  // overflowed
        val msg = s"Connection flow control window overflowed with update of $sizeIncrement"
        Error(FLOW_CONTROL_ERROR(msg, streamId, true))
      }
      else {
        // Allow all the nodes to attempt to write if they want to
        var r: MaybeError = Continue
        nodes().forall { node =>   // this will halt on an error (should never happen) and just return the error
          r = node.incrementOutboundWindow(0)
          oConnectionWindow() > 0 && r.success
        }
        r
      }
    }
    else getNode(streamId) match {
      case Some(node) => node.incrementOutboundWindow(sizeIncrement)
      case None       => Continue
    }
  }

  def onInitialWindowSizeChange(newWindow: Int): Unit = {
    val diff = newWindow - http2Settings.outboundInitialWindowSize
    logger.trace(s"Adjusting outbound windows by $diff")
    http2Settings.outboundInitialWindowSize = newWindow

    // We only adjust the stream windows
    // 6.9.2: The connection low-control window can only be changed using WINDOW_UPDATE frames.

    nodes().foreach { node =>
      node.incrementOutboundWindow(diff)
    }
  }
}
