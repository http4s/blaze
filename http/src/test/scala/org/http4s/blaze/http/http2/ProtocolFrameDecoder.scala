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

import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.http.http2.mocks.MockFrameListener

private[http2] object ProtocolFrameDecoder {
  private[this] class Listener(inHeaderSequence: Boolean)
      extends MockFrameListener(inHeaderSequence) {
    var frame: ProtocolFrame = ProtocolFrame.Empty

    override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: Array[Byte]): Result = {
      val ex = new Http2SessionException(errorCode, new String(debugData, StandardCharsets.UTF_8))
      frame = ProtocolFrame.GoAway(lastStream, ex)
      Continue
    }

    override def onPingFrame(ack: Boolean, data: Array[Byte]): Result = {
      frame = ProtocolFrame.Ping(ack, data)
      Continue
    }

    override def onSettingsFrame(settings: Option[Seq[Setting]]): Result = {
      frame = ProtocolFrame.Settings(settings)
      Continue
    }
  }

  def decode(
      buffer: ByteBuffer,
      inHeadersSequence: Boolean = false,
      maxFrameSize: Int = Http2Settings.default.maxFrameSize
  ): ProtocolFrame = {
    val listener = new Listener(inHeadersSequence)
    val decoder = new FrameDecoder(toSettings(maxFrameSize), listener)

    decoder.decodeBuffer(buffer) match {
      case Continue => listener.frame
      case BufferUnderflow => ProtocolFrame.Empty
      case Error(ex) => throw ex
    }
  }

  private[this] def toSettings(maxFrameSize: Int): Http2Settings =
    if (maxFrameSize == Http2Settings.default.maxFrameSize) Http2Settings.default
    else Http2Settings.default.copy(maxFrameSize = maxFrameSize)
}
