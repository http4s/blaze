package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.util

import org.http4s.blaze.http.http20.Http2Exception._
import org.http4s.blaze.http.http20.NodeMsg.Http2Msg
import org.http4s.blaze.pipeline.Command.OutboundCommand
import org.http4s.blaze.pipeline.{HeadStage, Command => Cmd}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Future, Promise}

/** Represents a basis for a Http2 Stream
  *
  * All operations should only be handled in a thread safe manner from
  * within the provided [[Http2StreamOps]]
  */
private[http20] final class Http2Stream(val streamId: Int,
                                        iStreamWindow: FlowWindow,
                                        oStreamWindow: FlowWindow,
                                        iConnectionWindow: FlowWindow,
                                        oConnectionWindow: FlowWindow,
                                        settings: Http2Settings,
                                        codec: Http20FrameDecoder with Http20FrameEncoder,
                                        ops: Http2StreamOps,
                                        headerEncoder: HeaderEncoder)
  extends HeadStage[Http2Msg]
{
  private sealed trait NodeState
  private case object Open extends NodeState
  private case object HalfClosed extends NodeState
  private case class CloseStream(t: Throwable) extends NodeState

  private var nodeState: NodeState = Open
  private var pendingOutboundFrames: (Promise[Unit], Seq[Http2Msg]) = null
  private var pendingInboundPromise: Promise[Http2Msg] = null
  private val pendingInboundMessages = new util.ArrayDeque[Http2Msg](16)

  private val msgEncoder = new NodeMsgEncoder(streamId, codec, headerEncoder)

  /////////////////////////////////////////////////////////////////////////////

  override def name: String = s"Http2Stream($streamId)"

  override def readRequest(size: Int): Future[Http2Msg] = ops.streamRead(this)

  override def writeRequest(data: Http2Msg): Future[Unit] = writeRequest(data::Nil)

  override def writeRequest(data: Seq[Http2Msg]): Future[Unit] = ops.streamWrite(this, data)

  override def outboundCommand(cmd: OutboundCommand): Unit = ops.streamCommand(this, cmd)

  /** Closes the queues of the stream */
  def closeStream(t: Throwable): Unit = {
    nodeState match {
      case CloseStream(Cmd.EOF) => nodeState = CloseStream(t)
      case CloseStream(_) => // NOOP
      case _ => nodeState = CloseStream(t)
    }

    if (pendingOutboundFrames != null) {
      val (p, _) = pendingOutboundFrames
      pendingOutboundFrames = null
      p.tryFailure(t)
    }

    if (pendingInboundPromise != null) {
      val p = pendingInboundPromise
      pendingInboundPromise = null
      p.tryFailure(t)
    }

    pendingInboundMessages.clear()
  }

  def isConnected(): Boolean = nodeState == Open || nodeState == HalfClosed

  /** Handle reading frames. This must be called from the ops to manage concurrency */
  def handleRead(): Future[Http2Msg] = nodeState match {
    case Open  =>
      if (pendingInboundPromise != null) {
        Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending read request"))
      } else {
        val data = pendingInboundMessages.poll()
        if (data == null) {
          val p = Promise[Http2Msg]
          pendingInboundPromise = p
          p.future
        }
        else  Future.successful(data)
      }

    case HalfClosed =>
      val data = pendingInboundMessages.poll()
      if (data == null) Future.failed(Cmd.EOF)
      else Future.successful(data)

    case CloseStream(t) => Future.failed(t)
  }

  /** Handle writing frames. This must be called from the ops to manage concurrency */
  def handleWrite(data: Seq[Http2Msg]): Future[Unit] = nodeState match {
    case Open | HalfClosed =>
      logger.trace(s"Node $streamId sending $data")

      if (pendingOutboundFrames != null) {
        Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending write request"))
      }
      else {
        val acc = new ArrayBuffer[ByteBuffer]()
        val rem = encodeMessages(data, acc)
        val f = ops.writeBuffers(acc)
        if (rem.isEmpty) f
        else {
          val p = Promise[Unit]
          pendingOutboundFrames = (p, rem)
          p.future
        }
      }

    case CloseStream(t) => Future.failed(t)
  }

  /** Stores the inbound message and deals with stream errors, if applicable */
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

      case HalfClosed => // This is a connection error
        val e = STREAM_CLOSED(streamId, fatal = true)
        closeStream(e)
        Error(e)

      case CloseStream(Cmd.EOF) => // May have been removed: extra messages.
        Error(STREAM_CLOSED(streamId, fatal = false))

      case CloseStream(t: Http2Exception) =>
        Error(t)

      case CloseStream(t) =>
        val msg = s"Stream($streamId) closed with error: ${t.getMessage}"
        Error(INTERNAL_ERROR(msg, fatal = true))
    }
  }

  /** Increments the window and checks to see if there are any pending messages to be sent */
  def incrementOutboundWindow(size: Int): MaybeError = {  // likely already acquired lock
    oStreamWindow.window += size

    if (oStreamWindow() < 0) {   // overflow
      val msg = s"Stream flow control window overflowed with update of $size"
      Error(FLOW_CONTROL_ERROR(msg, streamId, false))
    }
    else {
      if (oStreamWindow() > 0 && oConnectionWindow() > 0 && pendingOutboundFrames != null) {
        val (p, frames) = pendingOutboundFrames
        pendingOutboundFrames = null
        val acc = new ArrayBuffer[ByteBuffer]()
        val rem = encodeMessages(frames, acc)
        val f = ops.writeBuffers(acc)

        if (rem.isEmpty) p.completeWith(f)
        else {
          pendingOutboundFrames = (p, rem)
        }
      }
      Continue
    }
  }

  /** Decrements the inbound window and if not backed up, sends a window update if the level drops below 50% */
  private def decrementInboundWindow(size: Int): MaybeError = {
    if (size > iStreamWindow() || size > iConnectionWindow()) {
      Error(FLOW_CONTROL_ERROR("Inbound flow control overflow", streamId, fatal = true))
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

      if (buffs.nonEmpty) ops.writeBuffers(buffs)

      Continue
    }
  }

  // Mutates the state of the flow control windows
  private def encodeMessages(msgs: Seq[Http2Msg], acc: mutable.Buffer[ByteBuffer]): Seq[Http2Msg] = {
    val maxWindow = math.min(oConnectionWindow(), oStreamWindow())
    val (bytes, rem) = msgEncoder.encodeMessages(settings.maxFrameSize, maxWindow, msgs, acc)
    // track the bytes written and write the buffers
    oStreamWindow.window -= bytes
    oConnectionWindow.window -= bytes
    rem
  }
}
