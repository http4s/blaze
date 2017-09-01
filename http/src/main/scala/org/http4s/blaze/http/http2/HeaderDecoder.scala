package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.US_ASCII

import org.http4s.blaze.http.http2.Http2Exception._
import org.http4s.blaze.http.http2.Http2Settings.DefaultSettings
import org.http4s.blaze.util.BufferTools
import com.twitter.hpack.{Decoder, HeaderListener}

import scala.collection.immutable.VectorBuilder

/** Abstract representation of a `Headers` builder
  *
  * This is much like the scala collection `Builder`s with the
  * exception that we know things will come in pairs, but we don't
  * know the exact structure of that pair.
  *
  * @param maxHeaderListSize maximum allowed size of the header block
  * @param maxTableSize maximum compression table to maintain
  */
final class HeaderDecoder(maxHeaderListSize: Int, val maxTableSize: Int) { self =>
  require(maxTableSize >= DefaultSettings.HEADER_TABLE_SIZE)

  private[this] val acc = new VectorBuilder[(String, String)]
  private[this] var leftovers: ByteBuffer = null

  private[this] val decoder = new Decoder(maxHeaderListSize, maxTableSize)
  private[this] val listener = new HeaderListener {
    override def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit = {
      val k = new String(name, US_ASCII)
      val v = new String(value, US_ASCII)
      self.addHeader(k,v, sensitive)
    }
  }

  /** abstract method that adds the key value pair to the internal representation */
  protected def addHeader(name: String, value: String, sensitive: Boolean): Unit = {
    acc += ((name, value))
    ()
  }

  /** Set the HEADER_TABLE_SIZE parameter */
  def setMaxHeaderTableSize(max: Int): Unit = decoder.setMaxHeaderTableSize(max)

  /** Returns the header collection and clears the builder */
  def result(): Seq[(String,String)] = {
    val result = acc.result()
    acc.clear()
    result
  }

  /** Decode the headers into the internal header accumulator */
  def decode(buffer: ByteBuffer, streamId: Int, endHeaders: Boolean): MaybeError =
    doDecode(buffer, streamId, endHeaders, listener)

  /** Decode the headers, but discard the result. */
  def discard(buffer: ByteBuffer, streamId: Int, endHeaders: Boolean): MaybeError =
    doDecode(buffer, streamId, endHeaders, HeaderDecoder.nullListener)

  private[this] def doDecode(
    buffer: ByteBuffer,
    streamId: Int,
    endHeaders: Boolean,
    listener: HeaderListener): MaybeError = {
    try {
      val buff = BufferTools.concatBuffers(leftovers, buffer)
      decoder.decode(new ByteBufferInputStream(buff), listener)

      if (!buff.hasRemaining()) leftovers = null
      else if (buff ne buffer) leftovers = buff // we made a copy with concatBuffers
      else {  // buff == input buffer. Need to copy the input buffer so we are not sharing it
        val b = BufferTools.allocate(buff.remaining())
        b.put(buff).flip()
        leftovers = b
      }

      if (endHeaders) {
        decoder.endHeaderBlock()
      }

      Continue
    } catch { case t: Throwable =>
      Error(COMPRESSION_ERROR.goaway(s"Compression error on stream $streamId"))
    }
  }
}

private object HeaderDecoder {
  // Just discards the results.
  val nullListener: HeaderListener = new HeaderListener {
    override def addHeader(name: Array[Byte], value: Array[Byte], sensitive: Boolean): Unit = ()
  }
}



