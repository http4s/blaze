package org.http4s.blaze.http.http2

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.US_ASCII

import com.twitter.hpack.Encoder


/** Simple Headers type for use in blaze and testing */
class HeaderEncoder(private var maxTableSize: Int) {

//  require(maxTableSize <= DefaultSettings.HEADER_TABLE_SIZE, "Invalid initial table size")

  private[this] val encoder = new Encoder(getMaxTableSize)
  private[this] val os = new ByteArrayOutputStream(1024)


  /** Note that the default value is 4096 bytes */
  def getMaxTableSize(): Int = maxTableSize

  /** This should only be changed by the peer */
  def setMaxTableSize(max: Int): Unit = {
    maxTableSize = max
    encoder.setMaxHeaderTableSize(os, max)
  }

  def encodeHeaders(hs: Seq[(String, String)]): ByteBuffer = {
    hs.foreach { case (k,v) => encoder.encodeHeader(os, k.getBytes(US_ASCII), v.getBytes(US_ASCII), false) }
    val buff = ByteBuffer.wrap(os.toByteArray())
    os.reset()
    buff
  }
}
