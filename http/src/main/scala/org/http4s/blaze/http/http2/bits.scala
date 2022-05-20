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
import java.nio.charset.StandardCharsets

private[http2] object bits {
  val LengthFieldSize: Int = 3
  val HeaderSize: Int = 9

  val PrefaceString: String = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"

  def getPrefaceBuffer(): ByteBuffer =
    clientPrefaceBuffer.duplicate()

  object Masks {
    val INT31: Int = 0x7fffffff
    val INT32: Long = 0xffffffffL
    val EXCLUSIVE: Int = ~INT31
    val STREAMID: Int = INT31
    val LENGTH: Int = 0xffffff
  }

  object FrameTypes {
    val DATA: Byte = 0x00
    val HEADERS: Byte = 0x01
    val PRIORITY: Byte = 0x02
    val RST_STREAM: Byte = 0x03
    val SETTINGS: Byte = 0x04
    val PUSH_PROMISE: Byte = 0x05
    val PING: Byte = 0x06
    val GOAWAY: Byte = 0x07
    val WINDOW_UPDATE: Byte = 0x08
    val CONTINUATION: Byte = 0x09
  }

  // ////////////////////////////////////////////////

  object Flags {
    val END_STREAM: Byte = 0x1
    def END_STREAM(flags: Byte): Boolean =
      checkFlag(flags, END_STREAM) // Data, Header

    val PADDED: Byte = 0x8
    def PADDED(flags: Byte): Boolean = checkFlag(flags, PADDED) // Data, Header

    val END_HEADERS: Byte = 0x4
    def END_HEADERS(flags: Byte): Boolean =
      checkFlag(flags, END_HEADERS) // Header, push_promise

    val PRIORITY: Byte = 0x20
    def PRIORITY(flags: Byte): Boolean = checkFlag(flags, PRIORITY) // Header

    val ACK: Byte = 0x1
    def ACK(flags: Byte): Boolean = checkFlag(flags, ACK) // ping

    def DepID(id: Int): Int = id & Masks.STREAMID
    def DepExclusive(id: Int): Boolean = (Masks.EXCLUSIVE & id) != 0

    @inline
    private[this] def checkFlag(flags: Byte, flag: Byte) = (flags & flag) != 0
  }

  private[this] val clientPrefaceBuffer: ByteBuffer =
    ByteBuffer
      .wrap(PrefaceString.getBytes(StandardCharsets.UTF_8))
      .asReadOnlyBuffer()
}
