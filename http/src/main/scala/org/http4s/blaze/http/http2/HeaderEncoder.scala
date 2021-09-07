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

package org.http4s.blaze
package http
package http2

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.US_ASCII

import com.twitter.hpack.Encoder

/** HTTP/2 HPACK header encoder
  *
  * @param initialMaxTableSize
  *   maximum HPACK table size the peer will allow.
  */
class HeaderEncoder(initialMaxTableSize: Int) {
  private[this] val encoder = new Encoder(initialMaxTableSize)
  private[this] val os = new ByteArrayOutputStream(1024)

  /** This should only be changed by the peer */
  def maxTableSize(max: Int): Unit =
    encoder.setMaxHeaderTableSize(os, max)

  /** Encode the headers into the payload of a HEADERS frame */
  def encodeHeaders(hs: Headers): ByteBuffer = {
    hs.foreach { case (k, v) =>
      val keyBytes = k.getBytes(US_ASCII)
      val valueBytes = v.getBytes(US_ASCII)
      encoder.encodeHeader(os, keyBytes, valueBytes, false)
    }

    val buff = ByteBuffer.wrap(os.toByteArray())
    os.reset()
    buff
  }
}
