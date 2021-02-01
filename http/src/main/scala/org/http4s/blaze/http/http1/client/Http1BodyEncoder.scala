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

package org.http4s.blaze.http.http1.client

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.http4s.blaze.util.{BufferTools, ImmutableArray}

// Different encoders for the message body, either regular or transfer-encoding
private trait Http1BodyEncoder {
  // encode the buffer for the wire
  def encode(buffer: ByteBuffer): Seq[ByteBuffer]

  // generate any final data needed
  def finish(): ByteBuffer
}

private[client] object Http1BodyEncoder {
  // TODO: this should enforce conformance of the length-header
  object IdentityEncoder extends Http1BodyEncoder {
    override def finish(): ByteBuffer = BufferTools.emptyBuffer

    override def encode(buffer: ByteBuffer): Seq[ByteBuffer] = buffer :: Nil
  }

  // Prepends chunks with a length field
  object ChunkedTransferEncoder extends Http1BodyEncoder {
    override def finish(): ByteBuffer = terminator.duplicate()

    override def encode(buffer: ByteBuffer): Seq[ByteBuffer] = {
      val len = buffer.remaining()
      if (len == 0) Nil
      else ImmutableArray(Array(getLengthBuffer(len), buffer, crlf.duplicate()))
    }

    private def getLengthBuffer(length: Int): ByteBuffer = {
      val lenStr = Integer.toHexString(length)
      val buffer = BufferTools.allocate(lenStr.length + 2)
      var i = 0
      while (i < lenStr.length) {
        buffer.put(lenStr.charAt(i).toByte)
        i += 1
      }
      buffer.put('\r'.toByte).put('\n'.toByte)
      buffer.flip()
      buffer
    }

    private val crlf =
      ByteBuffer
        .wrap("\r\n".getBytes(StandardCharsets.UTF_8))
        .asReadOnlyBuffer()
    private val terminator =
      ByteBuffer
        .wrap("0\r\n\r\n".getBytes(StandardCharsets.UTF_8))
        .asReadOnlyBuffer()
  }
}
