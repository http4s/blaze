package org.http4s.blaze
package http
package parser

import scala.collection.mutable.ListBuffer
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.util.BufferTools

class ResponseParser extends Http1ClientParser {

  val headers = new ListBuffer[(String,String)]

  var code: Int = -1
  var reason = ""
  var scheme = ""
  var majorversion = -1
  var minorversion = -1

  /** Will not mutate the ByteBuffers in the Seq */
  def parseResponse(buffs: Seq[ByteBuffer]): (Int, Headers, String) =
    parseResponseBuffer(BufferTools.joinBuffers(buffs))

  /* Will mutate the ByteBuffer */
  def parseResponseBuffer(buffer: ByteBuffer): (Int, Seq[(String, String)], String) = {
    parseResponseLine(buffer)
    parseHeaders(buffer)

    if (!headersComplete()) sys.error("Headers didn't complete!")

    val body = new ListBuffer[ByteBuffer]
    while(!this.contentComplete() && buffer.hasRemaining) {
      body += parseContent(buffer)
    }

    val bp = {
      def toArray(buf: ByteBuffer): Array[Byte] = {
        val b = new Array[Byte](buf.remaining())
        buf.get(b)
        b
      }
      val bytes = body.foldLeft(Array[Byte]()) ((c1, c2) => c1 ++ toArray(c2))
      new String(bytes, StandardCharsets.ISO_8859_1)
    }

    val headers = this.headers.result

    val status = this.code

    (status, headers, bp)
  }


  override protected def headerComplete(name: String, value: String): Boolean = {
    headers += ((name,value))
    false
  }

  override protected def submitResponseLine(code: Int,
                                            reason: String,
                                            scheme: String,
                                            majorversion: Int,
                                            minorversion: Int): Unit = {
    this.code = code
    this.reason = reason
    this.majorversion = majorversion
    this.minorversion = minorversion
  }
}

object ResponseParser {
  def apply(buff: Seq[ByteBuffer]): (Int, Headers, String) = new ResponseParser().parseResponse(buff)
  def apply(buff: ByteBuffer): (Int, Headers, String) = parseBuffer(buff)

  def parseBuffer(buff: ByteBuffer): (Int, Headers, String) = new ResponseParser().parseResponseBuffer(buff)

  /** Make a String representation of the ByteBuffer, without modifying the buffer. */
  def bufferToString(in: ByteBuffer): String = {
    val sb = StringBuilder.newBuilder
    val buffer = in.asReadOnlyBuffer()  // Don't want to modify the original buffers positions or content
    while(buffer.hasRemaining) {
      sb.append(buffer.get().toChar)
    }
    sb.result()
  }
}
