package org.http4s.blaze.http.http20

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.US_ASCII

import com.twitter.hpack.Encoder
import org.http4s.blaze.http.http20.Http2Settings.DefaultSettings


/** Simple Headers type for use in blaze and testing */
final class HeaderEncoder(private var maxTableSize: Int = DefaultSettings.HEADER_TABLE_SIZE)
{
  require(maxTableSize <= DefaultSettings.HEADER_TABLE_SIZE, "Invalid initial table size")

  private val encoder = new Encoder(getMaxTableSize)
  private val os = new ByteArrayOutputStream(1024)


  /** Note that the default value is 4096 bytes */
  def getMaxTableSize(): Int = maxTableSize

  /** If this is changed, the peer must be notified */
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
