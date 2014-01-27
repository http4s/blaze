package blaze.pipeline.stages.spdy

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets._
import scala.annotation.tailrec
import blaze.util.ScratchBuffer

/**
 * @author Bryce Anderson
 *         Created on 1/27/14
 */
class SpdyHeaderEncoder {

  private def putHeaders(buff: ByteBuffer, headers: Map[String, Seq[String]]) {
    buff.putInt(headers.size)
    // Put all the data in there
    headers.foreach { case (k, v) =>
      buff.putInt(k.length)
        .put(k.toLowerCase.getBytes(US_ASCII))

      if (!v.isEmpty) {  // put the values in the buffer
      val pos = buff.position()
        buff.position(pos + 4)  // skip ahead of the value length

        v.foreach( v => buff.put(v.getBytes(US_ASCII)).put(0x0.toByte) )
        val keylen = buff.position() - pos - 1 - 4  // drop last byte
        buff.position(pos)

        buff.putInt(keylen)
        buff.position(pos + keylen + 4)  // skip to the end
      }
      else buff.putInt(0)   // No values
    }
  }

  /* Takes headers and returns a frash ByteBuffer with the compressed data */
  def encodeHeaders(headers: Map[String, Seq[String]]): ByteBuffer = {
    // compute the size of the header field
    val headerlen = headers.foldLeft(0){(i, pair) =>
      val pairlen = pair._2.foldLeft(0)(_ + _.length + 1)
      i + pair._1.length + pairlen + 8 - 1
    }

    // Compress the headers into a scratch buffer
    val scratch = ScratchBuffer.getScratchBuffer(headerlen + 50)
    putHeaders(scratch, headers)

    val deflater = new java.util.zip.Deflater

    try {
      deflater.setDictionary(spdyCompresionDict)

      deflater.setInput(scratch.array(), 0, scratch.position())
      deflater.finish()

      val out = new Array[Byte](headerlen + 50)

      // Store the data in the ByteBuffer wrapped array
      @tailrec
      def go(pos: Int): Int = {
        if (!deflater.finished()) {
          val sz = deflater.deflate(out, 0, out.length - pos)
          assert(sz > 0)
          go(sz + pos)
        } else pos
      }
      val len = go(0)

      if (len > 0xffffff)
        throw new ProtocolException(s"Compressed header length larger than 24 bit: $len")

      ByteBuffer.wrap(out, 0, len)
    }
    finally deflater.end()
  }

}
