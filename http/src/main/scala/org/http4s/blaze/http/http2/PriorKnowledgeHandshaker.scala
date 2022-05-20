/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.SettingsDecoder.SettingsFrame
import org.http4s.blaze.pipeline.TailStage
import org.http4s.blaze.util.{BufferTools, Execution}
import scala.concurrent.{ExecutionContext, Future}

/** Base type for performing the HTTP/2 prior knowledge handshake */
abstract class PriorKnowledgeHandshaker[T](localSettings: ImmutableHttp2Settings)
    extends TailStage[ByteBuffer] {
  final protected implicit def ec: ExecutionContext = Execution.trampoline

  override def name: String = s"${getClass.getSimpleName}($localSettings)"

  /** Handle the prior knowledge preface
    *
    * The preface is the magic string "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n" which is intended to cause
    * HTTP/1.x connections to fail gracefully. For clients, this involves sending the magic string
    * and for servers this consists of receiving the magic string. The return value of this function
    * is any unconsumed inbound data.
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

  private[this] def handleSettings(bytes: ByteBuffer): Future[(MutableHttp2Settings, ByteBuffer)] =
    sendSettings().flatMap(_ => readSettings(bytes))

  private[this] def sendSettings(): Future[Unit] = {
    val settingsBuffer = FrameSerializer.mkSettingsFrame(localSettings.toSeq)
    channelWrite(settingsBuffer)
  }

  // Attempt to read a SETTINGS frame and return it and any leftover data
  private[this] def readSettings(acc: ByteBuffer): Future[(MutableHttp2Settings, ByteBuffer)] = {
    logger.debug(s"receiving settings. Available data: $acc")

    def insufficientData = {
      logger.debug(
        s"Insufficient data. Current representation: " +
          BufferTools.hexString(acc, 256))
      channelRead().flatMap(buff => readSettings(BufferTools.concatBuffers(acc, buff)))
    }

    getFrameSize(acc) match {
      // received a (maybe partial) frame that exceeded the max allowed frame length
      // which is 9 bytes for the header and the length of the frame payload.
      case Some(size) if localSettings.maxFrameSize + bits.HeaderSize < size =>
        // The settings frame is too large so abort
        val ex = Http2Exception.FRAME_SIZE_ERROR.goaway(
          "While waiting for initial settings frame, encountered frame of " +
            s"size $size exceeded MAX_FRAME_SIZE (${localSettings.maxFrameSize})")
        logger.info(ex)(s"Received SETTINGS frame that was to large")
        sendGoAway(ex)

      case Some(frameSize) if acc.remaining < frameSize =>
        // Have the header but not a complete frame
        insufficientData

      case None =>
        // didn't have enough data for even the header
        insufficientData

      // We have at least a SETTINGS frame so we can process it
      case Some(size) =>
        val settingsBuffer = BufferTools.takeSlice(acc, size)
        SettingsDecoder.decodeSettingsFrame(settingsBuffer) match {
          case Right(SettingsFrame(Some(newSettings))) =>
            val remoteSettings = MutableHttp2Settings.default()
            remoteSettings.updateSettings(newSettings) match {
              case None =>
                logger.debug(
                  s"Successfully received settings frame. Current " +
                    s"remote settings: $remoteSettings")
                sendSettingsAck().map(_ => remoteSettings -> acc)

              case Some(ex) =>
                logger.info(ex)(s"Received SETTINGS frame but failed to update.")
                channelWrite(FrameSerializer.mkGoAwayFrame(0, ex)).flatMap { _ =>
                  Future.failed(ex)
                }
            }

          case Right(SettingsFrame(None)) => // was an ack! This is a PROTOCOL_ERROR
            logger.info(s"Received a SETTINGS ack frame which is a protocol error. Shutting down.")
            val ex = Http2Exception.PROTOCOL_ERROR.goaway(
              "Received a SETTINGS ack before receiving remote settings")
            sendGoAway(ex)

          case Left(http2Exception) => sendGoAway(http2Exception)
        }
    }
  }

  private[this] def sendGoAway(http2Exception: Http2Exception): Future[Nothing] = {
    val reply = FrameSerializer.mkGoAwayFrame(0, http2Exception)
    channelWrite(reply).flatMap { _ =>
      closePipeline(None)
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

  // Read the frame size from the buffer, and includes the header size (9 bytes).
  // If there isn't enough data for the frame header, `None` is returned.
  private[this] def getFrameSize(buffer: ByteBuffer): Option[Int] =
    if (buffer.remaining < bits.HeaderSize) None
    else {
      buffer.mark()
      val len = FrameDecoder.getLengthField(buffer)
      buffer.reset()

      if (len == -1) None
      else Some(len + bits.HeaderSize)
    }
}
