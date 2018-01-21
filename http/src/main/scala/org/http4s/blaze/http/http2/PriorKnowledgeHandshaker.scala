package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.pipeline.{Command, TailStage}
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.concurrent.Future

abstract class PriorKnowledgeHandshaker[T](localSettings: ImmutableHttp2Settings) extends TailStage[ByteBuffer] {

  final protected implicit def ec = Execution.trampoline

  override def name: String = s"${getClass.getSimpleName}($localSettings)"

  /** Handle the prior knowledge preface
    *
    * The preface is the magic string "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
    * which is intended to cause HTTP/1.x connections to fail gracefully.
    * For clients, this involves sending the magic string and for servers
    * this consists of receiving the magic string. The return value of this
    * function is any unconsumed inbound data.
    */
  protected def handlePreface(): Future[ByteBuffer]

  /** Perform pipeline manipulations necessary upon successful handshaking */
  protected def handshakeComplete(remoteSettings: MutableHttp2Settings, data: ByteBuffer): Future[T]

  /** Perform the HTTP/2 prior knowledge handshake */
  final def handshake(): Future[T] = {
    logger.debug("Beginning handshake.")
    handlePreface()
      .flatMap(handleSettings)
      .flatMap { case (settings, acc) =>
        handshakeComplete(settings, acc)
      }
  }

  private[this] def handleSettings(bytes: ByteBuffer): Future[(MutableHttp2Settings, ByteBuffer)] = {
    sendSettings().flatMap { _ => receiveSettings(bytes) }
  }

  private[this] def sendSettings(): Future[Unit] = {
    val settingsBuffer = FrameSerializer.mkSettingsFrame(localSettings.toSeq)
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
      case size if acc.remaining < size =>
        logger.debug {
          val str = StandardCharsets.UTF_8.decode(acc.duplicate())
          s"Insufficient data. Current representation: ${str}"
        }

        channelRead().flatMap { buff =>
          receiveSettings(BufferTools.concatBuffers(acc, buff))
        }

        // received an oversized frame
      case size if localSettings.maxFrameSize < size =>
        // The settings frame is too large so abort
        val ex = Http2Exception.FRAME_SIZE_ERROR.goaway(
          "While waiting for initial settings frame, encountered frame of " +
            s"size $size exceeded MAX_FRAME_SIZE (${localSettings.maxFrameSize})")
        sendHttp2GoAway(ex)

      case size =>  // we have at least a settings frame
        val settingsBuffer = BufferTools.takeSlice(acc, size)
        SettingsDecoder.decodeSettingsFrame(settingsBuffer) match {
          case Right(settingsFrame) if !settingsFrame.isAck =>
            val remoteSettings = MutableHttp2Settings.default()
            MutableHttp2Settings.updateSettings(remoteSettings, settingsFrame.settings) match {
              case None => sendSettingsAck().map { _ => remoteSettings -> acc }
              case Some(ex) =>
                // there was a problem with the settings: write it and fail.
                channelWrite(FrameSerializer.mkGoAwayFrame(0, ex)).flatMap { _ =>
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
    val reply = FrameSerializer.mkGoAwayFrame(0, http2Exception)
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
    val ackSettings = FrameSerializer.mkSettingsAckFrame()
    channelWrite(ackSettings)
  }

  private[this] def getFrameSize(buffer: ByteBuffer): Int = {
    if (buffer.remaining < bits.HeaderSize) -1
    else {
      buffer.mark()
      val len = FrameDecoder.getLengthField(buffer)
      buffer.reset()

      if (len == -1) len
      else len + bits.HeaderSize
    }
  }
}
