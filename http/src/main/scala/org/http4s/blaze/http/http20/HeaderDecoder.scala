package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.US_ASCII

import org.http4s.blaze.http.http20.Settings.DefaultSettings
import org.http4s.blaze.util.BufferTools

import com.twitter.hpack.{Decoder, HeaderListener}

/** Abstract representation of a `Headers` builder
  *
  * This is much like the scala collection `Builder`s with the
  * exception that we know things will come in pairs, but we don't
  * know the exact structure of that pair.
  *
  * @param maxHeaderSize maximum allowed size of a single header
  * @param maxTableSize maximum compression table to maintain
  * @tparam To the result of this builder
  */
abstract class HeaderDecoder[To](maxHeaderSize: Int,
                              val maxTableSize: Int) { self =>

  require(maxTableSize >= DefaultSettings.HEADER_TABLE_SIZE)

  private var leftovers: ByteBuffer = null

  /** abstract method that adds the key value pair to the internal representation */
  protected def addHeader(name: String, value: String, sensitive: Boolean): Unit

  /** Returns the header collection and clears the builder */
  def result(): To

  private val decoder = new Decoder(maxHeaderSize, maxTableSize)
  private val listener = new HeaderListener {
    override def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit = {
      val k = new String(name, US_ASCII)
      val v = new String(value, US_ASCII)
      self.addHeader(k,v, sensitive)
    }
  }

  final def decode(buffer: ByteBuffer): Unit = {
    val buff = BufferTools.concatBuffers(leftovers, buffer)
    val is = new ByteBufferInputStream(buff)
    decoder.decode(is, listener)

    if (!buff.hasRemaining()) leftovers = null
    else if (buff ne buffer) leftovers = buff // we made a copy with concatBuffers
    else {  // buff == input buffer. Need to copy the input buffer so we are not sharing it
      val b = BufferTools.allocate(buff.remaining())
      b.put(buff).flip()
      leftovers = b
    }
  }

  final def setMaxTableSize(max: Int): Unit = decoder.setMaxHeaderTableSize(max)
}



