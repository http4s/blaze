package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.util

import org.http4s.blaze.http.http20.Http2Exception._
import org.http4s.blaze.pipeline.{ Command => Cmd }
import org.log4s.Logger

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Future, Promise}

/** Represents a basis for a Http2 Stream
  *
  * All operations should only be handled in a thread safe manner
  */
private[http20] abstract class AbstractStream[T](val streamId: Int,
                                                iStreamWindow: FlowWindow,
                                                oStreamWindow: FlowWindow,
                                            iConnectionWindow: FlowWindow,
                                            oConnectionWindow: FlowWindow,
                                                     settings: Settings,
                                                        codec: Http20FrameDecoder with Http20FrameEncoder,
                                                headerEncoder: HeaderEncoder[T]) {

  private type Http2Msg = NodeMsg.Http2Msg[T]

  private sealed trait NodeState
  private case object Open extends NodeState
  private case object HalfClosed extends NodeState
  private case class CloseStream(t: Throwable) extends NodeState

  private var nodeState: NodeState = Open
  private var pendingOutboundFrames: (Promise[Unit], Seq[Http2Msg]) = null
  private var pendingInboundPromise: Promise[Http2Msg] = null
  private val pendingInboundMessages = new util.ArrayDeque[Http2Msg](16)

  private val msgEncoder = new NodeMsgEncoder[T](streamId, codec, headerEncoder)

  protected def logger: Logger

  protected def writeBuffers(data: Seq[ByteBuffer]): Future[Unit]

  def handleNodeCommand(cmd: Cmd.OutboundCommand): Unit

  /////////////////////////////////////////////////////////////////////////////

  def closeStream(t: Throwable): Unit = nodeState match {
    case CloseStream(_) => // NOOP
    case _ => nodeState = CloseStream(t)
  }

  def isConnected(): Boolean = nodeState == Open

  def handleRead(p: Promise[Http2Msg]): Unit = nodeState match {
    case Open  =>
      if (pendingInboundPromise != null) {
        p.failure(new IndexOutOfBoundsException("Cannot have more than one pending read request"))
      } else {
        val data = pendingInboundMessages.poll()
        if (data == null) {
          pendingInboundPromise = p
        }
        else  p.trySuccess(data)
      }

    case HalfClosed =>
      val data = pendingInboundMessages.poll()
      if (data == null) p.failure(Cmd.EOF)
      else p.trySuccess(data)

    case CloseStream(t) => p.failure(t)
  }

  def handleWrite(data: Seq[Http2Msg], p: Promise[Unit]): Unit = nodeState match {
    case Open | HalfClosed =>
      logger.trace(s"Node $streamId sending $data")

      if (pendingOutboundFrames != null) {
        p.failure(new IndexOutOfBoundsException("Cannot have more than one pending write request"))
      }
      else {
        val acc = new ArrayBuffer[ByteBuffer]()
        val rem = encodeMessages(data, acc)
        val f = writeBuffers(acc)
        if (rem.isEmpty) p.completeWith(f)
        else {
          pendingOutboundFrames = (p, rem)
        }
      }

    case CloseStream(t) => p.failure(t)
  }

  def inboundMessage(msg: Http2Msg, flowSize: Int, end_stream: Boolean): MaybeError = {
    nodeState match {
      case Open =>
        if (end_stream) nodeState = HalfClosed
        val r = decrementInboundWindow(flowSize)
        if (r.success) {
          if (pendingInboundPromise != null) {
            val p = pendingInboundPromise
            pendingInboundPromise = null
            p.success(msg)
          }
          else pendingInboundMessages.offer(msg)
        }
        r

      case HalfClosed =>
        Error(STREAM_CLOSED(streamId))

      case CloseStream(t: Http2Exception) => Error(t)

      case CloseStream(t) => Error(INTERNAL_ERROR(s"Stream($streamId) closed with error: ${t.getMessage}"))
    }
  }

  /** Increments the window and checks to see if there are any pending messages to be sent */
  def incrementOutboundWindow(size: Int): Unit = {  // likely already acquired lock
    oStreamWindow.window += size
    if (oStreamWindow() > 0 && oConnectionWindow() > 0 && pendingOutboundFrames != null) {
      val (p, frames) = pendingOutboundFrames
      pendingOutboundFrames = null
      val acc = new ArrayBuffer[ByteBuffer]()
      val rem = encodeMessages(frames, acc)
      val f = writeBuffers(acc)

      if (rem.isEmpty) p.completeWith(f)
      else {
        pendingOutboundFrames = (p, rem)
      }
    }
  }

  /** Decrements the inbound window and if not backed up, sends a window update if the level drops below 50% */
  private def decrementInboundWindow(size: Int): MaybeError = {
    if (size > iStreamWindow() || size > iConnectionWindow()) {
      Error(FLOW_CONTROL_ERROR("Inbound flow control overflow", streamId))
    } else {
      iStreamWindow.window -= size
      iConnectionWindow.window -= size

      // If we drop below 50 % and there are no pending messages, top it up
      val bf1 =
        if (iStreamWindow() < 0.5 * iStreamWindow.maxWindow && pendingInboundMessages.isEmpty) {
          val buff = codec.mkWindowUpdateFrame(streamId, iStreamWindow.maxWindow - iStreamWindow())
          iStreamWindow.window = iStreamWindow.maxWindow
          buff::Nil
        } else Nil

      // TODO: should we check to see if all the streams are backed up?
      val buffs =
        if (iConnectionWindow() < 0.5 * iConnectionWindow.maxWindow) {
          val buff = codec.mkWindowUpdateFrame(0, iConnectionWindow.maxWindow - iConnectionWindow())
          iConnectionWindow.window = iConnectionWindow.maxWindow
          buff::bf1
        } else bf1

      if (buffs.nonEmpty) writeBuffers(buffs)

      Continue
    }
  }

  // Mutates the state of the flow control windows
  private def encodeMessages(msgs: Seq[Http2Msg], acc: mutable.Buffer[ByteBuffer]): Seq[Http2Msg] = {
    val maxWindow = math.min(oConnectionWindow(), oStreamWindow())
    val (bytes, rem) = msgEncoder.encodeMessages(settings.max_frame_size, maxWindow, msgs, acc)
    // track the bytes written and write the buffers
    oStreamWindow.window -= bytes
    oConnectionWindow.window -= bytes
    rem
  }
}
