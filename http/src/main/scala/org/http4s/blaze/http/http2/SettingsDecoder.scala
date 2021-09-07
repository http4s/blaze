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

import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.http.http2.bits.{Flags, FrameTypes}

/** Utility for decoding a settings frame
  * @see
  *   https://tools.ietf.org/html/rfc7540#section-6.5
  */
private[blaze] object SettingsDecoder {

  /** Representation of a settings frame
    *
    * If the frame was an ack, settings is None, else it contains the settings sent by the peer
    */
  final case class SettingsFrame(settings: Option[Seq[Setting]]) {
    def isAck: Boolean = settings.isEmpty
  }

  /** Decode a settings frame
    *
    * @param buffer
    *   `ByteBuffer` consisting of exactly the frame, including the header.
    * @return
    *   A [[SettingsFrame]] or a [[Http2Exception]]
    */
  def decodeSettingsFrame(buffer: ByteBuffer): Either[Http2Exception, SettingsFrame] = {
    val len = FrameDecoder.getLengthField(buffer)
    assert(len + bits.HeaderSize - bits.LengthFieldSize == buffer.remaining())

    val tpe = buffer.get()

    if (tpe != FrameTypes.SETTINGS)
      Left(Http2Exception.PROTOCOL_ERROR.goaway("Expected SETTINGS frame"))
    else {
      val flags = buffer.get()
      val streamId = FrameDecoder.getStreamId(buffer)
      decodeSettingsFrame(buffer, streamId, flags)
    }
  }

  /** Decode the body of a SETTINGS frame
    *
    * @param buffer
    *   `ByteBuffer` containing exactly the body of the settings frame
    * @param streamId
    *   stream id obtained from the frame header. Must be 0x0.
    * @param flags
    *   flags obtained from the frame header.
    */
  def decodeSettingsFrame(
      buffer: ByteBuffer,
      streamId: Int,
      flags: Byte): Either[Http2Exception, SettingsFrame] = {
    import Http2Exception._

    val len = buffer.remaining
    val isAck = Flags.ACK(flags)

    if (len % 6 != 0) { // Invalid frame size
      val msg =
        s"SETTINGS frame payload must be multiple of 6 bytes, size: $len"
      Left(FRAME_SIZE_ERROR.goaway(msg))
    } else if (isAck && len != 0) {
      val settingsCount = len / 6 // 6 bytes per setting
      val msg =
        s"SETTINGS ACK frame with settings payload ($settingsCount settings)"
      Left(FRAME_SIZE_ERROR.goaway(msg))
    } else if (streamId != 0x0)
      Left(PROTOCOL_ERROR.goaway(s"SETTINGS frame with invalid stream id: $streamId"))
    else
      Right {
        // We have a valid frame
        if (isAck) SettingsFrame(None)
        else {
          val settings = Vector.newBuilder[Setting]
          while (buffer.hasRemaining) {
            val id: Int = buffer.getShort() & 0xffff
            val value = buffer.getInt()
            settings += Setting(id, value)
          }

          SettingsFrame(Some(settings.result()))
        }
      }
  }

  /** Create a [[MutableHttp2Settings]] from a collection of settings */
  def settingsFromFrame(settings: Seq[Setting]): Either[Http2Exception, MutableHttp2Settings] = {
    val next = MutableHttp2Settings.default()
    next.updateSettings(settings) match {
      case None => Right(next)
      case Some(ex) => Left(ex)
    }
  }
}
