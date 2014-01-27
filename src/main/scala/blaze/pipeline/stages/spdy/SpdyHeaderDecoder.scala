package blaze.pipeline.stages.spdy

import blaze.util.ScratchBuffer
import scala.collection.mutable.ListBuffer
import scala.annotation.tailrec
import java.nio.charset.StandardCharsets._
import java.nio.{BufferUnderflowException, ByteBuffer}

/**
 * @author Bryce Anderson
 *         Created on 1/26/14
 */
class SpdyHeaderDecoder(data: ByteBuffer) {

  private val start = data.position()
  private val end = data.limit()
  private val len = end - start

  println(s"$start, $end, $len")

  /* WARNING: this method returns a the data stored in a thread local
   * scratch buffer! Handle it on the stack! */
  private def inflate(): ByteBuffer = {

    val inflater = new java.util.zip.Inflater()

    try {
      // Set the data into the buffer
      if (data.hasArray) {
        inflater.setInput(data.array(), start, len)
      } else {
        val tmp = new Array[Byte](data.remaining())
        data.get(tmp)
        inflater.setInput(tmp)
      }

      val scratch = ScratchBuffer.getScratchBuffer(len * 7)

      var sz = inflater.inflate(scratch.array(), 0, scratch.capacity())

      if (sz == 0 && inflater.needsDictionary()) {
        inflater.setDictionary(spdyCompresionDict)
        sz += inflater.inflate(scratch.array(), 0, scratch.capacity())
      }

      println(s"Size: $sz")
      assert(inflater.finished)

      scratch.limit(sz)

      scratch.slice()
    }
    finally inflater.end()
  }

  private def decodeToHeaders(data: ByteBuffer): Map[String, Seq[String]] = {

    val headers = new ListBuffer[(String, Seq[String])]

    val headercount = data.getInt
    println("Headercount: " + headercount)

    @tailrec
    def decodeHeaderLoop(remaining: Int): Unit = if (remaining > 0) {
      val keylen = data.getInt

      if (keylen <= 0)
        throw new ProtocolException(s"Invalid Header Key-length: $keylen")

      val key = new String(data.array(), data.position(), keylen, US_ASCII)
      data.position(data.position() + keylen)

      val vallen = data.getInt

      if (vallen < 0)
        throw new ProtocolException(s"Invalid Header value length: $vallen")

      val vals = new ListBuffer[String]

      @tailrec
      def splitVals(start: Int, pos: Int, limit: Int) {
        if (pos < limit) {
          if (data.get == 0) {
            val s = new String(data.array(), start, pos - start, US_ASCII)
            vals += s
            splitVals(pos + 1, pos + 1, limit)
          } else splitVals(start, pos + 1, limit)
        } else { // at end of String
        val s = new String(data.array(), start, pos - start, US_ASCII)
          if (start != limit) vals += s
        }
      }

      splitVals(data.position(), data.position(), data.position() + vallen)
      headers += ((key, vals.result))

      decodeHeaderLoop(remaining - 1)
    }

    try decodeHeaderLoop(headercount)
    catch {
      case t: BufferUnderflowException =>
        throw new ProtocolException("Invalid header format")
    }

    headers.toMap
  }

  def decodeHeaders(): Map[String, Seq[String]] = {

    val scratch = inflate()

    decodeToHeaders(scratch)
  }

}
