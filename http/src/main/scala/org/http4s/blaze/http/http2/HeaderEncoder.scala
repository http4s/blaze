package org.http4s.blaze
package http
package http2

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.US_ASCII

import com.twitter.hpack.Encoder

/** HTTP/2 HPACK header encoder
  *
  * @param initialMaxTableSize maximum HPACK table size the peer
  *                            will allow.
  */
class HeaderEncoder(initialMaxTableSize: Int) {
  private[this] val encoder = new Encoder(initialMaxTableSize)
  private[this] val os = new ByteArrayOutputStream(1024)

  /** This should only be changed by the peer */
  def maxTableSize(max: Int): Unit =
    encoder.setMaxHeaderTableSize(os, max)

  /** Encode the headers into the payload of a HEADERS frame */
  def encodeHeaders(hs: Headers): ByteBuffer = {
    hs.foreach {
      case (k, v) =>
        val keyBytes = k.getBytes(US_ASCII)
        val valueBytes = v.getBytes(US_ASCII)
        encoder.encodeHeader(os, keyBytes, valueBytes, false)
    }

    val buff = ByteBuffer.wrap(os.toByteArray())
    os.reset()
    buff
  }
}
