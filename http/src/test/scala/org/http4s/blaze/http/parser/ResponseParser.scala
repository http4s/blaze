/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze
package http
package parser

import scala.collection.mutable.ListBuffer
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.util.BufferTools

class ResponseParser extends Http1ClientParser {
  val headers = new ListBuffer[(String, String)]

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
    while (!this.contentComplete() && buffer.hasRemaining)
      body += parseContent(buffer)

    val bp = {
      def toArray(buf: ByteBuffer): Array[Byte] = {
        val b = new Array[Byte](buf.remaining())
        buf.get(b)
        b
      }
      val bytes = body.foldLeft(Array[Byte]())((c1, c2) => c1 ++ toArray(c2))
      new String(bytes, StandardCharsets.ISO_8859_1)
    }

    val headers = this.headers.result()

    val status = this.code

    (status, headers, bp)
  }

  override protected def headerComplete(name: String, value: String): Boolean = {
    headers += ((name, value))
    false
  }

  override protected def submitResponseLine(
      code: Int,
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
  def apply(buff: Seq[ByteBuffer]): (Int, Headers, String) =
    new ResponseParser().parseResponse(buff)
  def apply(buff: ByteBuffer): (Int, Headers, String) = parseBuffer(buff)

  def parseBuffer(buff: ByteBuffer): (Int, Headers, String) =
    new ResponseParser().parseResponseBuffer(buff)

  /** Make a String representation of the ByteBuffer, without modifying the buffer. */
  def bufferToString(in: ByteBuffer): String = {
    val sb = new StringBuilder()
    val buffer =
      in.asReadOnlyBuffer() // Don't want to modify the original buffers positions or content
    while (buffer.hasRemaining)
      sb.append(buffer.get().toChar)
    sb.result()
  }
}
