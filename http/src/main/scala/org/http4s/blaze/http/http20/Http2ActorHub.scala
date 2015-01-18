package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets._

import org.http4s.blaze.http.http20.Settings.{ DefaultSettings => Default, Setting }
import org.http4s.blaze.pipeline.Command.OutboundCommand
import org.http4s.blaze.pipeline.{Command => Cmd, LeafBuilder, TailStage}
import org.http4s.blaze.pipeline.stages.addons.WriteSerializer
import org.http4s.blaze.util.{Execution, BufferTools, Actors}
import org.http4s.blaze.http.http20.bits.clientTLSHandshakeString

import scala.annotation.tailrec
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
  extends TailStage[ByteBuffer] with WriteSerializer[ByteBuffer] with Http2Stage[T]
{ hub =>

  private type Http2Msg = NodeMsg.Http2Msg[T]
  private type Stream = AbstractStream[T]

  sealed trait ActorMsg
  sealed trait StreamMsg extends ActorMsg { def stream: Stream }

  case object ShutdownCmd extends ActorMsg
  case class InboundBuffer(buffer: ByteBuffer) extends ActorMsg
  case class StreamWrite(stream: Stream, msgs: Seq[Http2Msg], p: Promise[Unit]) extends StreamMsg
  case class StreamRead(stream: Stream, p: Promise[Http2Msg]) extends StreamMsg
  case class StreamCmd(stream: Stream, cmd: Cmd.OutboundCommand) extends StreamMsg


  ///////////////////////////////////////////////////////////////////////////
  
  
  override def name: String = "Http2ConnectionStage"

  private val idManager = new StreamIdManager
  
  private val actor = Actors.make(actorMsg, actorFailure)(ec)

  private val frameHandler = new Http2FrameHandler[T](this, headerDecoder, headerEncoder, idManager,
                                                      inboundWindow, node_builder, maxInboundStreams)


  override def streamRead(stream: AbstractStream[T]): Future[Http2Msg] = {
    val p = Promise[Http2Msg]
    actor ! StreamRead(stream, p)
    p.future
  }

  override def writeBuffers(data: Seq[ByteBuffer]): Future[Unit] =
    channelWrite(data)

  override def streamWrite(stream: AbstractStream[T], data: Seq[Http2Msg]): Future[Unit] = {
    val p = Promise[Unit]
    actor ! StreamWrite(stream, data, p)
    p.future
  }

  override def streamCommand(stream: AbstractStream[T], cmd: OutboundCommand): Unit =
    actor ! StreamCmd(stream, cmd)

  override def shutdownConnection(): Unit = {
    stageShutdown()
    sendOutboundCommand(Cmd.Disconnect)
  }

  private def actorMsg(msg: ActorMsg): Unit = {
    logger.trace(s"Actor message: $msg")
    msg match {
      case InboundBuffer(buff) => decodeLoop(buff)

      case StreamRead(stream, p) => stream.handleRead(p)

      case StreamWrite(stream, data, p) => stream.handleWrite(data, p)

      case StreamCmd(stream, cmd) => stream.handleNodeCommand(cmd)

      case ShutdownCmd => frameHandler.flowControl.closeAllNodes()
    }
  }

  private def actorFailure(t: Throwable, msg: ActorMsg): Unit =
    onFailure(t, s"actorFailure($msg)")

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
    val buff = frameHandler.mkSettingsFrame(false, settings)

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
      val r = frameHandler.decodeBuffer(buff)
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

  def onFailure(t: Throwable, location: String): Unit = {
    logger.trace(t)("Failure: " + location)
    frameHandler.flowControl.closeAllNodes()
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
    channelWrite(frameHandler.mkRstStreamFrame(streamId, e.code))
  }


  private def sendGoAway(e: Http2Exception): Future[Unit] = {
    val lastStream = {
      val lastStream = idManager.lastClientId()
      if (lastStream > 0) lastStream else 1
    }

    val goAwayBuffs = frameHandler.mkGoAwayFrame(lastStream, e.code, e.msgBuffer())

    e.stream.foreach{ streamId => // make a RstStreamFrame, if possible.
      if (streamId > 0) sendRstStreamFrame(streamId, e)
    }

    channelWrite(goAwayBuffs, 10.seconds)
  }

  override protected def stageShutdown(): Unit = {
    actor ! ShutdownCmd
    super.stageShutdown()
  }
}
