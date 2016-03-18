package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets._

import org.http4s.blaze.http.http20.NodeMsg.Http2Msg
import org.http4s.blaze.http.http20.Settings.{ DefaultSettings => Default, Setting }
import org.http4s.blaze.pipeline.Command.OutboundCommand
import org.http4s.blaze.pipeline.{ Command => Cmd, LeafBuilder, TailStage }
import org.http4s.blaze.pipeline.stages.addons.WriteSerializer
import org.http4s.blaze.util.{ Execution, BufferTools }
import org.http4s.blaze.http.http20.bits.clientTLSHandshakeString

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{ Future, ExecutionContext }
import scala.util.{Failure, Success}

object Http2Stage {
  /** Construct a new Http2Stage */
  def apply(node_builder: Int => LeafBuilder[NodeMsg.Http2Msg],
            timeout: Duration,
            ec: ExecutionContext,
            maxHeadersLength: Int = 40*1024,
            maxInboundStreams: Int = Default.MAX_CONCURRENT_STREAMS,
            inboundWindow: Int = Default.INITIAL_WINDOW_SIZE,
            maxFrameSize: Int = Default.MAX_FRAME_SIZE): Http2Stage = {

    val headerDecoder = new HeaderDecoder(maxHeadersLength)
    val headerEncoder = new HeaderEncoder()
    val http2Settings = new Settings(inboundWindow = inboundWindow, max_inbound_streams = maxInboundStreams)

    new Http2Stage(node_builder, timeout, http2Settings, headerDecoder, headerEncoder, ec)
  }
}

class Http2Stage private(node_builder: Int => LeafBuilder[NodeMsg.Http2Msg],
                              timeout: Duration,
                        http2Settings: Settings,
                        headerDecoder: HeaderDecoder,
                        headerEncoder: HeaderEncoder,
                                   ec: ExecutionContext)
  extends TailStage[ByteBuffer] with WriteSerializer[ByteBuffer] with Http2StageConcurrentOps {

  ///////////////////////////////////////////////////////////////////////////

  private val lock = new AnyRef   // The only point of synchronization.
  
  override def name: String = "Http2ConnectionStage"

  private val idManager = new StreamIdManager

  private val frameHandler = new Http2FrameHandler(this, headerDecoder, headerEncoder, http2Settings, idManager)

  //////////////////////// Http2Stage methods ////////////////////////////////

  override def makePipeline(streamId: Int): LeafBuilder[Http2Msg] =
    node_builder(streamId)

  override def streamRead(stream: AbstractStream): Future[Http2Msg] =
    lock.synchronized { stream.handleRead() }

  override def streamWrite(stream: AbstractStream, data: Seq[Http2Msg]): Future[Unit] =
    lock.synchronized { stream.handleWrite(data) }

  override def streamCommand(stream: AbstractStream, cmd: OutboundCommand): Unit = lock.synchronized {

      def checkGoAway(): Unit = {
        if (http2Settings.receivedGoAway && frameHandler.flowControl.nodes().isEmpty) {  // we must be done
          stageShutdown()
          sendOutboundCommand(Cmd.Disconnect)
        }
      }

      cmd match {
        case Cmd.Disconnect =>
            frameHandler.flowControl.removeNode(stream.streamId, Cmd.EOF, false)
          checkGoAway()

        case Cmd.Error(t@Http2Exception(_, _, _, false)) =>
          streamError(stream.streamId, t)
          frameHandler.flowControl.removeNode(stream.streamId, Cmd.EOF, false)
          checkGoAway()

        case Cmd.Error(t) =>
          frameHandler.flowControl.removeNode(stream.streamId, Cmd.EOF, false)
          onFailure(t, s"handleNodeCommand(stream[${stream.streamId}])")

        case cmd =>
          logger.warn(s"$name is ignoring unhandled command ($cmd) from $this.") // Flush, Connect...
      }
  }

  // Doesn't need to be synchronized, leveraging the WriteSerializer
  override def writeBuffers(data: Seq[ByteBuffer]): Future[Unit] =
    channelWrite(data)


  ////////////////////////////////////////////////////////////////////////////

  // Startup
  override protected def stageStartup(): Unit = {
    super.stageStartup()

    implicit val ec = Execution.trampoline

    var newSettings: Vector[Setting] = Vector.empty

    if (http2Settings.max_inbound_streams != Default.MAX_CONCURRENT_STREAMS) {
      newSettings :+= Setting(Settings.MAX_CONCURRENT_STREAMS, http2Settings.max_inbound_streams)
    }

    if (http2Settings.inboundWindow != Default.INITIAL_WINDOW_SIZE) {
      newSettings :+= Setting(Settings.INITIAL_WINDOW_SIZE, http2Settings.inboundWindow)
    }

    if (headerDecoder.maxTableSize != Default.HEADER_TABLE_SIZE) {
      newSettings :+= Setting(Settings.HEADER_TABLE_SIZE, headerDecoder.maxTableSize)
    }

    logger.trace(s"Sending settings: " + newSettings)
    val buff = frameHandler.mkSettingsFrame(false, newSettings)

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
        decodeLoop(buff)
      } else {
        logger.info("HTTP/2.0: Failed to handshake, invalid header: " + header)
        onFailure(Cmd.EOF, "doHandshake")
      }
    }
  }

  // Will be called inside the actor thread
  private def decodeLoop(buff: ByteBuffer): Unit = lock.synchronized {
    logger.trace(s"Received buffer: $buff")
     @tailrec
    def go(): Unit = {
      val r = frameHandler.decodeBuffer(buff)
      logger.trace("Decoded buffer. Result: " + r)
      r match {
        case Continue => go()

        case BufferUnderflow =>
          channelRead().onComplete {
            case Success(b2) => decodeLoop(BufferTools.concatBuffers(buff, b2))
            case Failure(t) => onFailure(t, "ReadLoop")
          }(ec)

        case Error(ex@Http2Exception(_, _, Some(id), false)) =>
          streamError(id, ex)
          go()

        case Error(t) =>
          onFailure(t, "readLoop Error result")

        case Halt => // We are done
          stageShutdown()
          sendOutboundCommand(Cmd.Disconnect)
      }
    }

    try go()
    catch { case t: Throwable => onFailure(t, "readLoop uncaught exception") }
  }

  def onFailure(t: Throwable, location: String): Unit = {
    logger.debug(t)("Failure: " + location)
    t match {
      case Cmd.EOF =>
        sendOutboundCommand(Cmd.Disconnect)
        stageShutdown()

      case e: Http2Exception =>
        sendGoAway(e).onComplete { _ =>
          sendOutboundCommand(Cmd.Disconnect)
          stageShutdown()
        }(Execution.directec)

      case t: Throwable =>
        logger.error(t)(s"Unhandled error in $location")
        sendGoAway(Http2Exception.INTERNAL_ERROR(fatal = true)).onComplete{ _ =>
          sendOutboundCommand(Cmd.Error(t))
          stageShutdown()
        }(Execution.directec)
    }
  }

  private def sendGoAway(e: Http2Exception): Future[Unit] = {
    val lastStream = {
      val nodes = frameHandler.flowControl.nodes()
      nodes.foldLeft(idManager.lastClientId()){(i, n) =>
        val streamId = n.streamId
        frameHandler.flowControl.removeNode(streamId, Cmd.EOF, true)
        math.min(i, streamId)
      }
    }

    val goAwayBuffs = frameHandler.mkGoAwayFrame(lastStream, e.code, e.msgBuffer())

    e.stream.foreach{ streamId => // make a RstStreamFrame, if possible.
      if (streamId > 0) streamError(streamId, e)
    }

    channelWrite(goAwayBuffs, 10.seconds)
  }

  // Must be called in a thread safe manner
  private def streamError(streamId: Int, e: Http2Exception): Unit = {
    frameHandler.flowControl.removeNode(streamId, Cmd.EOF, true)
    channelWrite(frameHandler.mkRstStreamFrame(streamId, e.code))
  }

  //////////////////////////////////////////////////////////////////////////////////

  override protected def stageShutdown(): Unit = lock.synchronized {
    frameHandler.flowControl.closeAllNodes()
    super.stageShutdown()
  }
}
