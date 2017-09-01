package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.SettingsDecoder.SettingsFrame
import org.http4s.blaze.pipeline.{Command, TailStage}
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.concurrent.Future

abstract class PriorKnowledgeHandshaker[T](mySettings: ImmutableHttp2Settings) extends TailStage[ByteBuffer] {

  final protected implicit def ec = Execution.trampoline

  override def name: String = s"${getClass.getSimpleName}($mySettings)"

  protected def handlePrelude(): Future[ByteBuffer]

  protected def handshakeComplete(peerSettings: MutableHttp2Settings, data: ByteBuffer): Future[T]

  final def handshake(): Future[T] = {
    logger.debug("Beginning handshake.")

    handlePrelude()
      .flatMap(handleSettings)
      .flatMap { case (settings, acc) =>
        handshakeComplete(settings, acc)
      }
  }

  private[this] def handleSettings(bytes: ByteBuffer): Future[(MutableHttp2Settings, ByteBuffer)] = {
    sendSettings().flatMap { _ => receiveSettings(bytes) }
  }

  private[this] def sendSettings(): Future[Unit] = {
    val settingsBuffer = Http2FrameSerializer.mkSettingsFrame(mySettings.toSeq)
    channelWrite(settingsBuffer)
  }

  private[this] def receiveSettings(acc: ByteBuffer): Future[(MutableHttp2Settings, ByteBuffer)] = {
    logger.debug("receiving settings")
    Http2FrameDecoder.getFrameSize(acc) match {
      case -1 =>
        channelRead().flatMap { buff =>
          receiveSettings(BufferTools.concatBuffers(acc, buff))
        }

      // still need more data
      case size if acc.remaining() < size =>
        channelRead().flatMap { buff =>
          receiveSettings(BufferTools.concatBuffers(acc, buff))
        }

      case size =>  // we have at least a settings frame
        val settingsBuffer = BufferTools.takeSlice(acc, size)
        SettingsDecoder.decodeSettingsFrame(settingsBuffer) match {
          case Right(settingsFrame) if !settingsFrame.isAck =>
            val peerSettings = MutableHttp2Settings.default()
            MutableHttp2Settings.updateSettings(peerSettings, settingsFrame.settings) match {
              case None => sendSettingsAck().map { _ => peerSettings -> acc }
              case Some(ex) =>
                // there was a problem with the settings: write it and fail.
                channelWrite(Http2FrameSerializer.mkGoAwayFrame(0, ex)).flatMap { _ =>
                  Future.failed(ex)
                }
            }

          case Right(_) => // was an ack! This is a PROTOCOL_ERROR
            val ex = Http2Exception.PROTOCOL_ERROR.goaway(
              "Received a SETTINGS ack before receiving peer settings")
            sendHttp2GoAway(ex)


          case Left(http2Exception) => sendHttp2GoAway(http2Exception)
        }
    }
  }

  private[this] def sendHttp2GoAway(http2Exception: Http2Exception): Future[Nothing] = {
    val reply = Http2FrameSerializer.mkGoAwayFrame(0, http2Exception)
    channelWrite(reply).flatMap { _ =>
      sendOutboundCommand(Command.Disconnect)
      Future.failed(http2Exception)
    }
  }

  //  3.5.  HTTP/2 Connection Preface
  //    ...
  //  The SETTINGS frames received from a peer as part of the connection
  //  preface MUST be acknowledged (see Section 6.5.3) after sending the
  //  connection preface.
  private[this] def sendSettingsAck(): Future[Unit] = {
    val ackSettings = Http2FrameSerializer.mkSettingsAckFrame()
    channelWrite(ackSettings)
  }
}
