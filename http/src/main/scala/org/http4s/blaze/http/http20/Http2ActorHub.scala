package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets._
import java.util.HashMap

import org.http4s.blaze.http.http20.Settings.{ DefaultSettings => Default, Setting }
import org.http4s.blaze.http.http20.Http2Exception._
import org.http4s.blaze.pipeline.Command.OutboundCommand
import org.http4s.blaze.pipeline.{Command => Cmd, LeafBuilder, HeadStage, TailStage}
import org.http4s.blaze.pipeline.stages.addons.WriteSerializer
import org.http4s.blaze.util.{Execution, BufferTools, Actors}
import org.http4s.blaze.http.http20.bits.clientTLSHandshakeString

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Promise, Future, ExecutionContext}
import scala.util.{Failure, Success}

class Http2ActorHub[T](headerDecoder: HeaderDecoder[T],
                       headerEncoder: HeaderEncoder[T],
                        node_builder: () => LeafBuilder[NodeMsg.Http2Msg[T]],
                             timeout: Duration,
                   maxInboundStreams: Int = 300,
                       inboundWindow: Int = Default.INITIAL_WINDOW_SIZE,
                                  ec: ExecutionContext)
  extends TailStage[ByteBuffer] with WriteSerializer[ByteBuffer]
{ hub =>
  import FlowControl.Stream

  private type Http2Msg = NodeMsg.Http2Msg[T]

  sealed trait ActorMsg
  sealed trait StreamMsg extends ActorMsg { def stream: Stream }
  case object ShutdownCmd extends ActorMsg
  case class InboundBuffer(buffer: ByteBuffer) extends ActorMsg
  
  case class StreamWrite(stream: Stream, msgs: Seq[Http2Msg], p: Promise[Unit]) extends StreamMsg
  case class StreamRead(stream: Stream, p: Promise[Http2Msg]) extends StreamMsg
  case class StreamCmd(stream: Stream, cmd: Cmd.OutboundCommand) extends StreamMsg


  ///////////////////////////////////////////////////////////////////////////
  
  
  override def name: String = "Http2ConnectionStage"
  
  private val actor = Actors.make(actorMsg)(ec)
  private val nodeMap = new HashMap[Int, Stream]()

  private val codec = new Http20FrameCodec(FrameHandler) with HeaderHttp20Encoder {
    override type Headers = T
    override protected val headerEncoder = hub.headerEncoder
  }

  private val idManager = new StreamIdManager

  private var outbound_initial_window_size = Default.INITIAL_WINDOW_SIZE
  private var push_enable = Default.ENABLE_PUSH                           // initially enabled
  private var max_outbound_streams = Default.MAX_CONCURRENT_STREAMS       // initially unbounded.
  private var max_frame_size = Default.MAX_FRAME_SIZE
  private var max_header_size = Default.MAX_HEADER_LIST_SIZE              // initially unbounded

  private var receivedGoAway = false
  
  private def actorMsg(msg: ActorMsg): Unit = {
    logger.trace(s"Actor message: $msg")
    msg match {
      case InboundBuffer(buff) => decodeLoop(buff)

      case StreamRead(stream, p) => stream.handleRead(p)

      case StreamWrite(stream, data, p) => stream.handleWrite(data, p)

      case StreamCmd(stream, cmd) => stream.handleNodeCommand(cmd)

      case ShutdownCmd => closeAllNodes()
    }
  }

  private def closeAllNodes(): Unit = {
    val values = nodeMap.values().iterator()
    while (values.hasNext) {
      val node = values.next()
      values.remove()
      if (node.isConnected()) {
        node.closeStream(Cmd.EOF)
        node.inboundCommand(Cmd.Disconnected)
      }
    }
  }

  // Startup
  override protected def stageStartup() {
    super.stageStartup()

    implicit val ec = Execution.trampoline

    var settings = Vector.empty :+ Setting(Settings.MAX_CONCURRENT_STREAMS, maxInboundStreams)

    if (inboundWindow != Default.INITIAL_WINDOW_SIZE) {
      settings :+= Setting(Settings.INITIAL_WINDOW_SIZE, inboundWindow)
    }

    if (headerDecoder.maxTableSize != Default.HEADER_TABLE_SIZE) {
      settings :+= Setting(Settings.HEADER_TABLE_SIZE, headerDecoder.maxTableSize)
    }

    logger.trace(s"Sending settings: " + settings)
    val buff = codec.mkSettingsFrame(false, settings)

    channelWrite(buff, timeout).flatMap(_ => channelRead()).onComplete {
      case Success(buff) => doHandshake(buff)
      case Failure(t)    => onFailure(t, "stageStartup")
    }
  }

  // This should only be called once at at startup
  private def doHandshake(buff: ByteBuffer): Unit = {
    logger.trace(s"Handshaking: $buff")

    if (buff.remaining() < clientTLSHandshakeString.length) {
      channelRead(clientTLSHandshakeString.length - buff.remaining()).onComplete {
        case Success(b2) => doHandshake(BufferTools.concatBuffers(buff, b2))
        case Failure(t)  => onFailure(t, "processHandshake")
      }(Execution.trampoline)
    } else {
      val l = buff.limit()
      val p = buff.position()
      buff.limit(p + clientTLSHandshakeString.length)
      val header = UTF_8.decode(buff.slice()).toString()
      logger.trace("Received header string: " + header)

      if (header == clientTLSHandshakeString) {
        logger.trace("Handshake complete. Entering readLoop")
        buff.limit(l).position(p + clientTLSHandshakeString.length)
        actor ! InboundBuffer(buff)
      } else {
        logger.info("HTTP/2.0: Failed to handshake, invalid header: " + header)
        onFailure(Cmd.EOF, "doHandshake")
      }
    }
  }

  // Will be called inside the actor thread
  private def decodeLoop(buff: ByteBuffer): Unit = {
    logger.trace(s"Received buffer: $buff")
     @tailrec
    def go(): Unit = {
      val r = codec.decodeBuffer(buff)
      logger.trace("Decoded buffer. Result: " + r)
      r match {
        case Continue => go()
        case BufferUnderflow =>
          channelRead().onComplete {
            case Success(b2) => actor ! InboundBuffer(BufferTools.concatBuffers(buff, b2))
            case Failure(t) => onFailure(t, "ReadLoop")
          }(ec)

        case Halt => // NOOP  only called on shutdown
        case Error(t) => onFailure(t, "readLoop Error result")
      }
    }

    try go()
    catch { case t: Throwable => onFailure(t, "readLoop uncaught exception") }
  }

  private def onFailure(t: Throwable, location: String): Unit = {
    logger.trace(t)("Failure: " + location)
    closeAllNodes()
    t match {
      case Cmd.EOF =>
        sendOutboundCommand(Cmd.Disconnect)
        stageShutdown()

      case e: Http2Exception =>
        sendGoAway(e).onComplete { _ =>
          sendOutboundCommand(Cmd.Disconnect)
          stageShutdown()
        }(ec)

      case t: Throwable =>
        logger.error(t)(s"Unhandled error in $location")
        sendOutboundCommand(Cmd.Error(t))
        stageShutdown()
    }
  }

  private def sendRstStreamFrame(streamId: Int, e: Http2Exception): Unit = {
    channelWrite(codec.mkRstStreamFrame(streamId, e.code))
  }


  private def sendGoAway(e: Http2Exception): Future[Unit] = {
    val lastStream = {
      val lastStream = idManager.lastClientId()
      if (lastStream > 0) lastStream else 1
    }

    val goAwayBuffs = codec.mkGoAwayFrame(lastStream, e.code, e.msgBuffer())

    e.stream.foreach{ streamId => // make a RstStreamFrame, if possible.
      if (streamId > 0) sendRstStreamFrame(streamId, e)
    }

    channelWrite(goAwayBuffs, 10.seconds)
  }

    override protected def stageShutdown(): Unit = {
      actor ! ShutdownCmd
      super.stageShutdown()
    }


  /////////////////////////// Stream management //////////////////////////////////////

  private def getNode(streamId: Int): Option[Stream] = Option(nodeMap.get(streamId))

  private def nodeCount(): Int = nodeMap.size()

  private def removeNode(streamId: Int): Option[Stream] = {
    val node = nodeMap.remove(streamId)
    if (node != null) {
      if (node.isConnected()) node.inboundCommand(Cmd.Disconnected)
      Some(node)
    }
    else None
  }

  private def nodes(): Seq[Stream] =
    mutable.WrappedArray.make(nodeMap.values().toArray())

  ///////////////////////// The FrameHandler decides how to decode incoming frames ///////////////////////

  // All the methods in here will be called from `codec` and thus synchronization can be managed through codec calls
  private object FrameHandler extends DecodingFrameHandler {
    override type HeaderType = T

    override protected val headerDecoder = hub.headerDecoder

    override def onCompletePushPromiseFrame(streamId: Int, promisedId: Int, headers: HeaderType): Http2Result =
      Error(PROTOCOL_ERROR("Server received a PUSH_PROMISE frame from a client", streamId))

    override def onCompleteHeadersFrame(streamId: Int,
                                        priority: Option[Priority],
                                        end_stream: Boolean,
                                        headers: HeaderType): Http2Result =
    {
      val msg = NodeMsg.HeadersFrame(priority, end_stream, headers)

      getNode(streamId) match {
        case None =>
          if (!idManager.validateClientId(streamId)) Error(PROTOCOL_ERROR(s"Invalid streamId", streamId))
          else if (nodeCount() >= maxInboundStreams) {
            Error(FLOW_CONTROL_ERROR(s"MAX_CONCURRENT_STREAMS setting exceeded: ${nodeCount()}"))
          }
          else {
            val node = FlowControl.makeStream(streamId)
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

      nodes().foreach { node =>
        if (node.streamId > lastStream) removeNode(node.streamId)
        else liveNodes = true
      }

      if (liveNodes) {    // No more streams allowed, but keep the connection going.
        receivedGoAway = true
        Continue
      }
      else {
        sendOutboundCommand(Cmd.Disconnect)
        stageShutdown()
        Halt
      }
    }

    override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result = {
      channelWrite(codec.mkPingFrame(true, data))
      Continue
    }

    override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = {
      logger.trace(s"Received settings frames: $settings, ACK: $ack")
      if (ack) Continue    // TODO: ensure client sends acknolegments?
      else {
        val r = processSettings(settings)
        if (r.success) {
          val buff = codec.mkSettingsFrame(true, Nil)
          logger.trace("Writing settings ACK")
          channelWrite(buff) // write the ACK settings frame
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
            codec.setEncoderMaxTable(v.toInt)
            Continue

          case Setting(Settings.ENABLE_PUSH, v) =>
            if (v == 0) { push_enable = false; Continue }
            else if (v == 1) {  push_enable = true; Continue }
            else Error(PROTOCOL_ERROR(s"Invalid ENABLE_PUSH setting value: $v"))

          case Setting(Settings.MAX_CONCURRENT_STREAMS, v) =>
            if (v > Integer.MAX_VALUE) {
              Error(PROTOCOL_ERROR(s"To large MAX_CONCURRENT_STREAMS: $v"))
            } else { max_outbound_streams = v.toInt; Continue }

          case Setting(Settings.INITIAL_WINDOW_SIZE, v) =>
            if (v > Integer.MAX_VALUE) Error(FLOW_CONTROL_ERROR(s"Invalid initial window size: $v"))
            else { FlowControl.onInitialWindowSizeChange(v.toInt); Continue }

          case Setting(Settings.MAX_FRAME_SIZE, v) =>
            // max of 2^24-1 http/2.0 draft 16 spec
            if (v < Default.MAX_FRAME_SIZE || v > 16777215) Error(PROTOCOL_ERROR(s"Invalid frame size: $v"))
            else { max_frame_size = v.toInt; Continue }


          case Setting(Settings.MAX_HEADER_LIST_SIZE, v) =>
            if (v > Integer.MAX_VALUE) Error(PROTOCOL_ERROR(s"SETTINGS_MAX_HEADER_LIST_SIZE to large: $v"))
            else { max_header_size = v.toInt; Continue }

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
      val node = removeNode(streamId)
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
      getNode(streamId) match {
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
      FlowControl.onWindowUpdateFrame(streamId, sizeIncrement)
    }
  }

  ///////////////////////// FrameHandler /////////////////////////////

  protected object FlowControl {

    private val oConnectionWindow = new FlowWindow(outbound_initial_window_size)
    private val iConnectionWindow = new FlowWindow(inboundWindow)

    def makeStream(streamId: Int): Stream = {
      val stream = node_builder().base(new Stream(streamId))
      nodeMap.put(streamId, stream)
      stream.inboundCommand(Cmd.Connected)
      stream
    }

    def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): MaybeError = {
      logger.debug(s"Updated window of stream $streamId by $sizeIncrement. ConnectionOutbound: $oConnectionWindow")

      if (sizeIncrement <= 0) {
        if (streamId == 0) Error(PROTOCOL_ERROR(s"Invalid WINDOW_UPDATE size: $sizeIncrement"))
        else {
          channelWrite(codec.mkRstStreamFrame(streamId, PROTOCOL_ERROR.code))
          getNode(streamId).foreach(node => removeNode(node.streamId))
          Continue
        }
      }
      else if (streamId == 0) {
        oConnectionWindow.window += sizeIncrement

        // Allow all the nodes to attempt to write if they want to
        nodes().forall { node =>
          node.incrementOutboundWindow(0)
          oConnectionWindow() > 0
        }
        Continue
      }
      else {
        getNode(streamId).foreach(_.incrementOutboundWindow(sizeIncrement))
        Continue
      }
    }

    def onInitialWindowSizeChange(newWindow: Int): Unit = {
      val diff = newWindow - outbound_initial_window_size
      logger.trace(s"Adjusting outbound windows by $diff")
      outbound_initial_window_size = newWindow
      oConnectionWindow.window += diff

      nodes().foreach { node =>
        node.incrementOutboundWindow(diff)
      }
    }
    
    final class Stream(streamId: Int)
        extends SmallStream[T](streamId,
          new FlowWindow(inboundWindow),
          new FlowWindow(outbound_initial_window_size),
          iConnectionWindow,
          oConnectionWindow,
          max_frame_size, codec, headerEncoder) with HeadStage[Http2Msg] {

      override def name: String = s"Stream[$streamId]"

      def startNode(): Unit = {
        inboundCommand(Cmd.Connected)
      }

      ///////////////////////////////////////////////////////////////

      def handleNodeCommand(cmd: Cmd.OutboundCommand): Unit = {
        def checkGoAway(): Unit = {
          if (receivedGoAway && nodes().isEmpty) {  // we must be done
            stageShutdown()
            sendOutboundCommand(Cmd.Disconnect)
          }
        }

        cmd match {
          case Cmd.Disconnect =>
            removeNode(streamId)
            checkGoAway()

          case Cmd.Error(t: Http2Exception) =>
            sendRstStreamFrame(streamId, t)
            removeNode(streamId)
            checkGoAway()

          case Cmd.Error(t) =>
            onFailure(t, s"handleNodeCommand(stream[$streamId])")

          case cmd            => logger.warn(s"$name is ignoring unhandled command ($cmd) from $this.") // Flush, Connect...
        }
      }

      override def outboundCommand(cmd: OutboundCommand): Unit =
        actor ! StreamCmd(this, cmd)

      override protected def channelWrite(data: Seq[ByteBuffer]): Future[Unit] = hub.channelWrite(data)

      override def readRequest(size: Int): Future[Http2Msg] = {
        val p = Promise[Http2Msg]
        actor ! StreamRead(this, p)
        p.future
      }

      override def writeRequest(data: Http2Msg): Future[Unit] = writeRequest(data :: Nil)

      override def writeRequest(data: Seq[Http2Msg]): Future[Unit] = {
        val p = Promise[Unit]
        actor ! StreamWrite(this, data, p)
        p.future
      }
    }
  }
}
