package org.http4s.blaze.http.http2

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
  private[this] var _maxTableSize = initialMaxTableSize
  private[this] val encoder = new Encoder(maxTableSize)
  private[this] val os = new ByteArrayOutputStream(1024)


  /** The current value of SETTINGS_HEADER_TABLE_SIZE */
  def maxTableSize: Int = _maxTableSize

  /** This should only be changed by the peer */
  def maxTableSize(max: Int): Unit = {
    _maxTableSize = max
    encoder.setMaxHeaderTableSize(os, max)
  }

  /** Encode the headers into the payload of a HEADERS frame */
  def encodeHeaders(hs: Seq[(String, String)]): ByteBuffer = {
    hs.foreach { case (k,v) =>
      val keyBytes = k.getBytes(US_ASCII)
      val valueBytes = v.getBytes(US_ASCII)
      encoder.encodeHeader(os, keyBytes, valueBytes, false)
    }

    val buff = ByteBuffer.wrap(os.toByteArray())
    os.reset()
    buff
  }
}
