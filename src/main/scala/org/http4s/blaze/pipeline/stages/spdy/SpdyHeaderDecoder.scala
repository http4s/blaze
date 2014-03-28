package org.http4s.blaze.pipeline.stages.spdy

import org.http4s.blaze.util.ScratchBuffer
import scala.collection.mutable.ListBuffer
import scala.annotation.tailrec
import java.nio.charset.StandardCharsets._
import java.nio.{BufferUnderflowException, ByteBuffer}

/**
 * @author Bryce Anderson
 *         Created on 1/26/14
 */
class SpdyHeaderDecoder {

  private val inflater = new java.util.zip.Inflater()

  def decodeHeaders(data: ByteBuffer): Map[String, Seq[String]] = {

    val scratch = inflate(data)
    decodeToHeaders(scratch)
  }

  /* WARNING: this method returns a the data stored in a thread local
   * scratch buffer! Handle it on the stack! */
  private def inflate(data: ByteBuffer): ByteBuffer = {
    try {

      // Load the data into the inflater. We will use the scratch buffer for double duty
      val len = data.remaining()
      val scratch = ScratchBuffer.getScratchBuffer(20*len)

      scratch.position(19*len)     // store the data at the end
      scratch.put(data)

      var arr = scratch.array()

      inflater.setInput(arr, 19*len, len)

      def go(start: Int, space: Int): Int = {
        val sz = {
          val sz = inflater.inflate(arr, start, space)
          if (sz == 0 && inflater.needsDictionary()) {
            // I think this should only happen once
            inflater.setDictionary(spdyCompresionDict)
            inflater.inflate(arr, start, space)
          }
          else sz
        }

        if (sz == space) {  // we need more space to decode
        val newsz = start + sz
          val n = new Array[Byte](arr.length*2)
          System.arraycopy(arr, 0, n, 0, newsz)
          arr = n
          go(newsz, n.length - newsz)
        }
        else start + sz
      }

      val sz = go(0, 9*len)

//      println(s"%%%%%%%%%%%%%%% Inflated $sz bytes from $len bytes %%%%%%%%%%%%%%%%%%%")

      ByteBuffer.wrap(arr, 0, sz)
    }
    catch { case t: Throwable => close(); throw t }
  }

  def close() {
    inflater.end()
  }

  private def decodeToHeaders(data: ByteBuffer): Map[String, Seq[String]] = {



    val headercount = data.getInt

//    println(s"%%%%%%%%%%%%%%% Decoding $headercount headers %%%%%%%%%%%%%%%%%%%%%%")

    val sb = new StringBuffer(512)
    val vals = new ListBuffer[String]
    val headers = new ListBuffer[(String, Seq[String])]

    @tailrec
    def decodeHeaderLoop(remaining: Int): Unit = {
      if (remaining > 0) {
        val keylen = data.getInt

        if (keylen <= 0)
          throw new ProtocolException(s"Invalid Header Key-length: $keylen")

        val key = getString(data, keylen)

        val vallen = data.getInt

        if (vallen < 0)
          throw new ProtocolException(s"Invalid Header value length: $vallen")

        @tailrec
        def splitVals(pos: Int, limit: Int) {
          if (pos < limit) {
            val b = data.get
            if (b != 0) sb.append(b.toChar)
            else {
              vals += sb.toString
              sb.setLength(0)
            }
            splitVals(pos + 1, limit)
          } else { // at end of String
            if (sb.length() > 0) {
              vals += sb.toString
              sb.setLength(0)
            }
          }
        }

        splitVals(0, vallen)
        headers += ((key, vals.result))
        vals.clear()

        decodeHeaderLoop(remaining - 1)
      }
    }

    try decodeHeaderLoop(headercount)
    catch {
      case t: BufferUnderflowException =>
        throw new ProtocolException(s"Invalid header format resulted in BufferUnderflow.\n" +
          headers.result() + "\n" + vals.result())
    }

    headers.toMap
  }

  private def getString(buff: ByteBuffer, len: Int): String = {
    if(len > 4*1024) throw new ProtocolException(s"Invalid Header value length: $len")
    ByteBuffer.allocate(43)
    val strArr = new Array[Byte](len)
    buff.get(strArr)
    new String(strArr, US_ASCII)
  }
}
