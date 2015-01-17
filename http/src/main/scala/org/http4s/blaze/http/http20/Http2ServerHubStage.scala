//package org.http4s.blaze.http.http20
//
//import java.nio.ByteBuffer
//import java.nio.channels.NotYetConnectedException
//import java.nio.charset.StandardCharsets.UTF_8
//import java.util
//
//import org.http4s.blaze.pipeline.Command.{EOF, OutboundCommand}
//import org.http4s.blaze.pipeline.stages.addons.WriteSerializer
//import org.http4s.blaze.pipeline.{Command => Cmd, LeafBuilder}
//import org.http4s.blaze.pipeline.stages.HubStage
//import org.http4s.blaze.util.BufferTools
//import org.http4s.blaze.util.Execution.trampoline
//
//import Settings.{ DefaultSettings => Default, Setting }
//
//import scala.annotation.tailrec
//import scala.collection.mutable
//import scala.collection.mutable.ArrayBuffer
//import scala.concurrent.{Promise, Future}
//import scala.concurrent.duration._
//import scala.util.{Failure, Success}
//
//// TODO: is it best to stick with extending the WriteSerializer?
//final class Http2ServerHubStage[HType](headerDecoder: HeaderDecoder[HType],
//                                       headerEncoder: HeaderEncoder[HType],
//                                        node_builder: () => LeafBuilder[NodeMsg.Http2Msg[HType]],
//                                             timeout: Duration,
//                                   maxInboundStreams: Int,
//                                       inboundWindow: Int = Default.INITIAL_WINDOW_SIZE)
//  extends HubStage[ByteBuffer] with WriteSerializer[ByteBuffer]
//{ hub =>
//
//  import Http2Exception._ // used by pretty much all the error handling
//  import bits._
//
//  require(maxInboundStreams > 0, "Invalid max streams")
//
//  override def name: String = "Http2ServerHubStage"
//
//  private implicit def ec = trampoline
//
//  type Http2Msg = NodeMsg.Http2Msg[HType]
//
//  override type Key = Int
//  override type Out = Http2Msg
//  override protected type NodeT = FlowControl.NodeState
//
//  // Using synchronization and this is the lock
//  private val lock = new AnyRef
//
//  //////////////////////// The connection state ///////////////////////////////////
//
//  private val idManager = new StreamIdManager
//
//  private var outbound_initial_window_size = Default.INITIAL_WINDOW_SIZE
//  private var push_enable = Default.ENABLE_PUSH                           // initially enabled
//  private var max_outbound_streams = Default.MAX_CONCURRENT_STREAMS       // initially unbounded.
//  private var max_frame_size = Default.MAX_FRAME_SIZE
//  private var max_header_size = Default.MAX_HEADER_LIST_SIZE              // initially unbounded
//
//  private var receivedGoAway = false
//
//  /////////////////////////////////////////////////////////////////////////////////
//
//  private val codec = new Http20FrameCodec(FrameHandler) with HeaderHttp20Encoder {
//    override type Headers = HType
//    override protected val headerEncoder = hub.headerEncoder
//  }
//
//  private def makeNode(id: Int): NodeT = super.makeNode(id, node_builder())
//    .getOrElse(sys.error(s"Attempted to make stream that already exists"))
//
//  // Startup
//  override protected def stageStartup() {
//    super.stageStartup()
//
//    var settings = Vector.empty :+ Setting(Settings.MAX_CONCURRENT_STREAMS, maxInboundStreams)
//
//    if (inboundWindow != Default.INITIAL_WINDOW_SIZE) {
//      settings :+= Setting(Settings.INITIAL_WINDOW_SIZE, inboundWindow)
//    }
//
//    if (headerDecoder.maxTableSize != Default.HEADER_TABLE_SIZE) {
//      settings :+= Setting(Settings.HEADER_TABLE_SIZE, headerDecoder.maxTableSize)
//    }
//
//    logger.trace(s"Sending settings: " + settings)
//    val buff = lock.synchronized(codec.mkSettingsFrame(false, settings))
//
//    channelWrite(buff, timeout).flatMap(_ => channelRead()).onComplete {
//      case Success(buff) => doHandshake(buff)
//      case Failure(t)    => onFailure(t, "stageStartup")
//    }
//  }
//
//  // This should only be called once at at startup
//  private def doHandshake(buff: ByteBuffer): Unit = {
//    logger.trace(s"Handshaking: $buff")
//
//    if (buff.remaining() < clientTLSHandshakeString.length) {
//      channelRead(clientTLSHandshakeString.length - buff.remaining()).onComplete {
//        case Success(b2) => doHandshake(BufferTools.concatBuffers(buff, b2))
//        case Failure(t)  => onFailure(t, "processHandshake")
//      }
//    } else {
//      val l = buff.limit()
//      val p = buff.position()
//      buff.limit(p + clientTLSHandshakeString.length)
//      val header = UTF_8.decode(buff.slice()).toString()
//      logger.trace("Received header string: " + header)
//
//      if (header == clientTLSHandshakeString) {
//        logger.trace("Handshake complete. Entering readLoop")
//        buff.limit(l).position(p + clientTLSHandshakeString.length)
//        readLoop(buff)
//      } else {
//        logger.info("HTTP/2.0: Failed to handshake, invalid header: " + header)
//        onFailure(Cmd.EOF, "doHandshake")
//      }
//    }
//  }
//
//  override protected def stageShutdown(): Unit = {
//    closeAllNodes()
//    super.stageShutdown()
//  }
//
//  // TODO: this is probably wrong
//  private def readLoop(buff: ByteBuffer): Unit = {
//    logger.trace(s"Received buffer: $buff")
//      def go(): Unit = {
//        val r = lock.synchronized(codec.decodeBuffer(buff))
//        logger.trace("Decoded buffer. Result: " + r)
//        r match {
//          case Continue => go()
//          case BufferUnderflow =>
//            channelRead().onComplete {
//              case Success(b2) => readLoop(BufferTools.concatBuffers(buff, b2))
//              case Failure(t) => onFailure(t, "ReadLoop")
//            }
//
//          case Halt => // NOOP
//          case Error(t) => onFailure(t, "readLoop Error result")
//        }
//      }
//
//    try go()
//    catch { case t: Throwable => onFailure(t, "readLoop uncaught exception") }
//  }
//
//  ///////////////////////// The FrameHandler decides how to decode incoming frames ///////////////////////
//
//  // All the methods in here will be called from `codec` and thus synchronization can be managed through codec calls
//  private object FrameHandler extends DecodingFrameHandler {
//    override type HeaderType = HType
//
//    override protected val headerDecoder = hub.headerDecoder
//
//    override def onCompletePushPromiseFrame(streamId: Int, promisedId: Int, headers: HeaderType): Http2Result =
//      Error(PROTOCOL_ERROR("Server received a PUSH_PROMISE frame from a client", streamId))
//
//    override def onCompleteHeadersFrame(streamId: Int,
//                                        priority: Option[Priority],
//                                      end_stream: Boolean,
//                                         headers: HeaderType): Http2Result =
//    {
//      val msg = NodeMsg.HeadersFrame(priority, end_stream, headers)
//
//      getNode(streamId) match {
//        case None =>
//          if (!idManager.validateClientId(streamId)) Error(PROTOCOL_ERROR(s"Invalid streamId", streamId))
//          else if (nodeCount() >= maxInboundStreams) {
//            Error(FLOW_CONTROL_ERROR(s"MAX_CONCURRENT_STREAMS setting exceeded: ${nodeCount()}"))
//          }
//          else {
//            val node = makeNode(streamId)
//            node.startNode()
//            node.inboundMessage(msg, 0, end_stream)
//          }
//
//        case Some(node) => node.inboundMessage(msg, 0, end_stream)
//      }
//    }
//
//    override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Http2Result = {
//
//      val errStr = UTF_8.decode(debugData).toString()
//      if (errorCode != NO_ERROR.code) {
//        logger.warn(s"Received GOAWAY(NO_ERROR) frame, msg: '$errStr'")
//      }
//      else {
//        logger.warn(s"Received error ${Http2Exception.get(errorCode.toInt)}, msg: $errStr")
//      }
//
//      var liveNodes = false
//
//      nodes().foreach { node =>
//        if (node.key > lastStream) removeNode(node)
//        else liveNodes = true
//      }
//
//      if (liveNodes) {    // No more streams allowed, but keep the connection going.
//        receivedGoAway = true
//        Continue
//      }
//      else {
//        sendOutboundCommand(Cmd.Disconnect)
//        stageShutdown()
//        Halt
//      }
//    }
//
//    override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result = {
//      channelWrite(codec.mkPingFrame(true, data), timeout)
//      Continue
//    }
//
//    override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = {
//      logger.trace(s"Received settings frames: $settings, ACK: $ack")
//      if (ack) Continue    // TODO: ensure client sends acknolegments?
//      else {
//        val r = processSettings(settings)
//        if (r.success) {
//          val buff = codec.mkSettingsFrame(true, Nil)
//          logger.trace("Writing settings ACK")
//          channelWrite(buff, timeout) // write the ACK settings frame
//        }
//        r
//      }
//    }
//
//    @tailrec
//    private def processSettings(settings: Seq[Setting]): MaybeError = {
//      if (settings.isEmpty) Continue
//      else {
//        val r = settings.head match {
//          case Setting(Settings.HEADER_TABLE_SIZE, v) =>
//            codec.setEncoderMaxTable(v.toInt)
//            Continue
//
//          case Setting(Settings.ENABLE_PUSH, v) =>
//            if (v == 0) { push_enable = false; Continue }
//            else if (v == 1) {  push_enable = true; Continue }
//            else Error(PROTOCOL_ERROR(s"Invalid ENABLE_PUSH setting value: $v"))
//
//          case Setting(Settings.MAX_CONCURRENT_STREAMS, v) =>
//            if (v > Integer.MAX_VALUE) {
//              Error(PROTOCOL_ERROR(s"To large MAX_CONCURRENT_STREAMS: $v"))
//            } else { max_outbound_streams = v.toInt; Continue }
//
//          case Setting(Settings.INITIAL_WINDOW_SIZE, v) =>
//            if (v > Integer.MAX_VALUE) Error(FLOW_CONTROL_ERROR(s"Invalid initial window size: $v"))
//            else { FlowControl.onInitialWindowSizeChange(v.toInt); Continue }
//
//          case Setting(Settings.MAX_FRAME_SIZE, v) =>
//            // max of 2^24-1 http/2.0 draft 16 spec
//            if (v < Default.MAX_FRAME_SIZE || v > 16777215) Error(PROTOCOL_ERROR(s"Invalid frame size: $v"))
//            else { max_frame_size = v.toInt; Continue }
//
//
//          case Setting(Settings.MAX_HEADER_LIST_SIZE, v) =>
//            if (v > Integer.MAX_VALUE) Error(PROTOCOL_ERROR(s"SETTINGS_MAX_HEADER_LIST_SIZE to large: $v"))
//            else { max_header_size = v.toInt; Continue }
//
//          case Setting(k, v) =>
//            logger.warn(s"Unknown setting ($k, $v)")
//            Continue
//        }
//        if (r.success) processSettings(settings.tail)
//        else r
//      }
//    }
//
//    // For handling unknown stream frames
//    override def onExtensionFrame(tpe: Int, streamId: Int, flags: Byte, data: ByteBuffer): Http2Result =
//      Error(PROTOCOL_ERROR(s"Unsupported extension frame: type: $tpe, " +
//                           s"Stream ID: $streamId, flags: $flags, data: $data", streamId))
//
//    override def onRstStreamFrame(streamId: Int, code: Int): Http2Result = {
//      val codeName = errorName(code)
//      val node = removeNode(streamId)
//      if (node.isEmpty) {
//
//        if (idManager.lastClientId() < streamId) {
//          logger.warn(s"Client attempted to reset idle stream: $streamId, code: $codeName")
//          Error(PROTOCOL_ERROR("Attempted to RST idle stream"))
//        }
//        else {
//          logger.info(s"Client attempted to reset closed stream: $streamId, code: $codeName")
//          Continue
//        }
//      }
//      else {
//        logger.info(s"Stream $streamId reset with code $codeName")
//        Continue
//      }
//
//    }
//
//    override def onDataFrame(streamId: Int, end_stream: Boolean, data: ByteBuffer, flowSize: Int): Http2Result = {
//      getNode(streamId) match {
//        case Some(node) =>
//          val msg = NodeMsg.DataFrame(end_stream, data)
//          node.inboundMessage(msg, flowSize, end_stream)
//
//        case None =>
//          if (streamId <= idManager.lastClientId()) {
//            channelWrite(codec.mkRstStreamFrame(streamId, STREAM_CLOSED.code))
//            Continue
//          }  // NOOP, might be old stream
//          else Error(PROTOCOL_ERROR(s"DATA frame on invalid stream: $streamId", streamId))
//      }
//    }
//
//    override def onPriorityFrame(streamId: Int, priority: Priority): Http2Result = {
//      // TODO: should we implement some type of priority handling?
//      Continue
//    }
//
//    override def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): Http2Result = {
//      FlowControl.onWindowUpdateFrame(streamId, sizeIncrement)
//    }
//  }
//
//  ///////////////////////////// Error Handling stuff //////////////////////////////////////////
//
//  private def onFailure(t: Throwable, location: String): Unit = {
//    logger.trace(t)("Failure: " + location)
//    t match {
//      case Cmd.EOF =>
//        sendOutboundCommand(Cmd.Disconnect)
//        stageShutdown()
//
//      case e: Http2Exception =>
//        sendGoAway(e).onComplete { _ =>
//          sendOutboundCommand(Cmd.Disconnect)
//          stageShutdown()
//        }
//
//      case t: Throwable =>
//        logger.error(t)(s"Unhandled error in $location")
//        sendOutboundCommand(Cmd.Error(t))
//        stageShutdown()
//    }
//  }
//
//  private def sendRstStreamFrame(streamId: Int, e: Http2Exception): Unit = {
//    channelWrite(codec.mkRstStreamFrame(streamId, e.code))
//  }
//
//
//  private def sendGoAway(e: Http2Exception): Future[Unit] = lock.synchronized {
//    val lastStream = {
//      val lastStream = idManager.lastClientId()
//      if (lastStream > 0) lastStream else 1
//    }
//
//    val goAwayBuffs = codec.mkGoAwayFrame(lastStream, e.code, e.msgBuffer())
//
//    val buffs = e.stream.foreach{ streamId => // make a RstStreamFrame, if possible.
//      if (streamId > 0) sendRstStreamFrame(streamId, e)
//    }
//
//    channelWrite(goAwayBuffs, 10.seconds)
//  }
//
//  /////////////////////////////////////////////////////////////////////////////////////////////
//
//  protected object FlowControl {
//
//    private var outboundConnectionWindow = outbound_initial_window_size
//    private var inboundConnectionWindow = inboundWindow
//
//    def newNodeState(id: Int): NodeState = new NodeState(id, outbound_initial_window_size)
//
//    def onWindowUpdateFrame(streamId: Int, sizeIncrement: Int): MaybeError = {
//
//      logger.debug(s"Updated window of stream $streamId by $sizeIncrement. ConnectionOutbound: $outboundConnectionWindow")
//
//      if (sizeIncrement <= 0) {
//        if (streamId == 0) Error(PROTOCOL_ERROR(s"Invalid WINDOW_UPDATE size: $sizeIncrement"))
//        else {
//          channelWrite(codec.mkRstStreamFrame(streamId, PROTOCOL_ERROR.code))
//          getNode(streamId).foreach(removeNode)
//          Continue
//        }
//      }
//      else if (streamId == 0) {
//        outboundConnectionWindow += sizeIncrement
//
//        // Allow all the nodes to attempt to write if they want to
//        nodes().forall { node =>
//          node.incrementOutboundWindow(0)
//          outboundConnectionWindow > 0
//        }
//        Continue
//      }
//      else {
//        getNode(streamId).foreach(_.incrementOutboundWindow(sizeIncrement))
//        Continue
//      }
//    }
//
//    def onInitialWindowSizeChange(newWindow: Int): Unit = {
//      val diff = newWindow - outbound_initial_window_size
//      logger.trace(s"Adjusting outbound windows by $diff")
//      outbound_initial_window_size = newWindow
//      outboundConnectionWindow += diff
//
//      nodes().foreach { node =>
//        node.incrementOutboundWindow(diff)
//      }
//    }
//
//    ////////////////////////////////////////////////////////////
//
//    class NodeState private[Http2ServerHubStage](val key: Int, private var outboundWindow: Int)
//        extends NodeMsgEncoder[HType](key, codec, headerEncoder) with Node
//    {
//      private sealed trait NodeState
//      private case object NotInitialized extends NodeState
//      private case object Open extends NodeState
//      private case class CloseStream(t: Throwable) extends NodeState
//
//      private var streamInboundWindow: Int = inboundWindow
//
//      private var nodeState: NodeState = NotInitialized
//
//      private var pendingOutboundFrames: (Promise[Unit], Seq[Http2Msg]) = null
//
//      private var pendingInboundPromise: Promise[Http2Msg] = null
//      private val pendingInboundMessages = new util.ArrayDeque[Http2Msg](16)
//
//
//      override def isConnected(): Boolean = lock.synchronized { nodeState == Open }
//
//      /** Receives outbound commands
//        * Override to capture commands. */
//      override def outboundCommand(cmd: OutboundCommand): Unit = lock.synchronized {
//        def checkGoAway(): Unit = {
//          if (receivedGoAway && nodes().isEmpty) {  // we must be done
//            stageShutdown()
//            sendOutboundCommand(Cmd.Disconnect)
//          }
//        }
//
//        cmd match {
//          case Cmd.Disconnect =>
//            removeNode(this)
//            checkGoAway()
//
//          case Cmd.Error(t: Http2Exception) =>
//            sendRstStreamFrame(key, t)
//            removeNode(this)
//            checkGoAway()
//
//
//          case e@Cmd.Error(t) => logger.error(t)(s"Received error from node $key"); sendOutboundCommand(e)
//          case cmd            => logger.warn(s"$name is ignoring unhandled command ($cmd) from $this.")  // Flush, Connect...
//        }
//      }
//
//
//      override def writeRequest(data: Seq[Http2Msg]): Future[Unit] = lock.synchronized ( nodeState match {
//        case Open =>
//          logger.trace(s"Node $key sending $data")
//
//          if (pendingOutboundFrames != null) {
//            Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending write request"))
//          }
//          else {
//            val acc = new ArrayBuffer[ByteBuffer]()
//            val rem = encodeMessages(data, acc)
//            val f = channelWrite(acc, timeout)
//            if (rem.isEmpty) f
//            else {
//              val p = Promise[Unit]
//              pendingOutboundFrames = (p, rem)
//              p.future
//            }
//          }
//
//        case NotInitialized => onNotReady()
//
//        case CloseStream(t) => Future.failed(t)
//      })
//
//      override def readRequest(size: Int): Future[Http2Msg] = lock.synchronized (nodeState match {
//        case Open =>
//          if (pendingInboundPromise != null) {
//            Future.failed(new IndexOutOfBoundsException("Cannot have more than one pending read request"))
//          } else {
//            val data = pendingInboundMessages.poll()
//            if (data == null) {
//              pendingInboundPromise = Promise[Http2Msg]
//              pendingInboundPromise.future
//            }
//            else  Future.successful(data)
//          }
//
//        case NotInitialized => onNotReady()
//
//        case CloseStream(t) => Future.failed(t)
//      })
//
//      def inboundMessage(msg: Http2Msg, flowSize: Int, end_stream: Boolean): MaybeError = nodeState match {
//        case Open =>
//          if (end_stream) nodeState = CloseStream(EOF)
//          val r = decrementInboundWindow(flowSize)
//          if (r.success) {
//            if (pendingInboundPromise != null) {
//              val p = pendingInboundPromise
//              pendingInboundPromise = null
//              p.success(msg)
//            }
//            else pendingInboundMessages.offer(msg)
//          }
//          r
//
//        case NotInitialized => Error(INTERNAL_ERROR(s"Message to uninitialized stream($key): $msg"))
//
//        case CloseStream(EOF) => Error(STREAM_CLOSED(key))
//
//        case CloseStream(t: Http2Exception) => Error(t)
//
//        case CloseStream(t) => Error(INTERNAL_ERROR(s"Stream($key) closed with error: ${t.getMessage}"))
//      }
//
//      /////////////////////// Window tracking ////////////////////////////////
//
//      // Increments the window and checks to see if there are any pending messages to be sent
//      def incrementOutboundWindow(size: Int): Unit = lock.synchronized {  // likely already acquired lock
//        outboundWindow += size
//        if (outboundWindow > 0 && outboundConnectionWindow > 0 && pendingOutboundFrames != null) {
//          val (p, frames) = pendingOutboundFrames
//          pendingOutboundFrames = null
//          val acc = new ArrayBuffer[ByteBuffer]()
//          val rem = encodeMessages(frames, acc)
//          val f = channelWrite(acc, timeout)
//
//          if (rem.isEmpty) p.completeWith(f)
//          else {
//            pendingOutboundFrames = (p, rem)
//          }
//        }
//      }
//
//      // Decrements the inbound window and if not backed up, sends a window update if the level drops below 50%
//      private def decrementInboundWindow(size: Int): MaybeError = {
//        if (size > streamInboundWindow || size > inboundConnectionWindow) {
//          Error(FLOW_CONTROL_ERROR("Inbound flow control overflow", key))
//        } else {
//          streamInboundWindow -= size
//          inboundConnectionWindow -= size
//
//          // If we drop below 50 % and there are no pending messages, top it up
//          val bf1 =
//            if (streamInboundWindow < 0.5 * inboundWindow && pendingInboundMessages.isEmpty) {
//              val buff = codec.mkWindowUpdateFrame(key, inboundWindow - streamInboundWindow)
//              streamInboundWindow = inboundWindow
//              buff::Nil
//            } else Nil
//
//          // TODO: should we check to see if all the streams are backed up?
//          val buffs =
//            if (inboundConnectionWindow < 0.5 * inboundWindow) {
//              val buff = codec.mkWindowUpdateFrame(0, inboundWindow - inboundConnectionWindow)
//              inboundConnectionWindow = inboundWindow
//              buff::bf1
//            } else bf1
//
//          if (buffs.nonEmpty) channelWrite(buffs, timeout)
//
//          Continue
//        }
//      }
//
//      // Mutates the state of the flow control windows
//      private def encodeMessages(msgs: Seq[Http2Msg], acc: mutable.Buffer[ByteBuffer]): Seq[Http2Msg] = {
//        val maxWindow = math.min(outboundConnectionWindow, outboundWindow)
//        val (bytes, rem) = super.encodeMessages(max_frame_size, maxWindow, msgs, acc)
//        // track the bytes written and write the buffers
//        outboundWindow -= bytes
//        outboundConnectionWindow -= bytes
//        rem
//      }
//
//      private def onNotReady(): Future[Nothing] = {
//          logger.error(s"Disconnected node with key $key attempting IO request")
//          Future.failed(new NotYetConnectedException)
//      }
//
//    }
//    ////////////////////////////////////////////////////////////////////////////////
//
//  }
//}
//
