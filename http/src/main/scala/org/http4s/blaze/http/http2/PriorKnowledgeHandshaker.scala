package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.SettingsDecoder.SettingsFrame
import org.http4s.blaze.pipeline.{Command, TailStage}
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.concurrent.Future

abstract class PriorKnowledgeHandshaker[T](localSettings: ImmutableHttp2Settings) extends TailStage[ByteBuffer] {

  final protected implicit def ec = Execution.trampoline

  override def name: String = s"${getClass.getSimpleName}($localSettings)"

  protected def handlePrelude(): Future[ByteBuffer]

  protected def handshakeComplete(remoteSettings: MutableHttp2Settings, data: ByteBuffer): Future[T]

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
    val settingsBuffer = Http2FrameSerializer.mkSettingsFrame(localSettings.toSeq)
    channelWrite(settingsBuffer)
  }

  private[this] def receiveSettings(acc: ByteBuffer): Future[(MutableHttp2Settings, ByteBuffer)] = {
    logger.debug("receiving settings")
    getFrameSize(acc) match {
      case -1 =>
        channelRead().flatMap { buff =>
          receiveSettings(BufferTools.concatBuffers(acc, buff))
        }

      // still need more data
      case size if acc.remaining() < size =>
        logger.debug {
          val b = acc.duplicate()
          val sb = new StringBuilder
          while (b.hasRemaining) {
            sb.append(b.get.toChar)
          }

          s"Insufficient data: ${sb.result()}"
        }

        channelRead().flatMap { buff =>
          receiveSettings(BufferTools.concatBuffers(acc, buff))
        }

      case size =>  // we have at least a settings frame
        val settingsBuffer = BufferTools.takeSlice(acc, size)
        SettingsDecoder.decodeSettingsFrame(settingsBuffer) match {
          case Right(settingsFrame) if !settingsFrame.isAck =>
            val remoteSettings = MutableHttp2Settings.default()
            MutableHttp2Settings.updateSettings(remoteSettings, settingsFrame.settings) match {
              case None => sendSettingsAck().map { _ => remoteSettings -> acc }
              case Some(ex) =>
                // there was a problem with the settings: write it and fail.
                channelWrite(Http2FrameSerializer.mkGoAwayFrame(0, ex)).flatMap { _ =>
                  Future.failed(ex)
                }
            }

          case Right(_) => // was an ack! This is a PROTOCOL_ERROR
            val ex = Http2Exception.PROTOCOL_ERROR.goaway(
              "Received a SETTINGS ack before receiving remote settings")
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

  private[this] def getFrameSize(buffer: ByteBuffer): Int = {
    if (buffer.remaining < bits.HeaderSize) -1
    else {
      buffer.mark()
      val len = Http2FrameDecoder.getLengthField(buffer)
      buffer.reset()

      if (len == -1) len
      else len + bits.HeaderSize
    }
  }
}
