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

package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable
import scala.util.control.NoStackTrace

sealed abstract class Http2Exception(msg: String)
    extends Exception(msg)
    with NoStackTrace
    with Product
    with Serializable {
  // A 32-bit unsigned Integer
  def code: Long

  final def name: String = Http2Exception.errorName(code)

  /** Convert this exception to a stream exception
    *
    * @note
    *   If this is already a stream exception but with a different stream id, the id will be changed
    */
  final def toStreamException(streamId: Int): Http2StreamException =
    this match {
      case ex: Http2StreamException if ex.stream == streamId => ex
      case ex => new Http2StreamException(streamId, ex.code, ex.getMessage)
    }

  /** Convert this exception to a session exception */
  final def toSessionException(): Http2SessionException =
    this match {
      case Http2StreamException(_, code, msg) => Http2SessionException(code, msg)
      case ex: Http2SessionException => ex
    }

  /** Was the exception due to refusal by the peer.
    *
    * These exceptions are safe to automatically retry even if the HTTP method is not an idempotent
    * method. See https://tools.ietf.org/html/rfc7540#section-8.1.4 for more details.
    */
  final def isRefusedStream: Boolean =
    code == Http2Exception.REFUSED_STREAM.code

  /** serialize the message as a `ByteBuffer` */
  final def msgBuffer(): ByteBuffer = ByteBuffer.wrap(msg.getBytes(UTF_8))
}

final case class Http2StreamException(stream: Int, code: Long, msg: String)
    extends Http2Exception(msg)

final case class Http2SessionException(code: Long, msg: String) extends Http2Exception(msg)

///////////////////// HTTP/2.0 Errors //////////////////////////////
object Http2Exception {
  final class ErrorGenerator private[http2] (val code: Long, val name: String) {

    /** Create a Http2Exception with stream id 0 */
    def goaway(): Http2Exception = Http2SessionException(code, name)

    /** Create a Http2Exception with stream id 0 */
    def goaway(msg: String): Http2SessionException =
      Http2SessionException(code, name + ": " + msg)

    /** Create a Http2Exception with the requisite stream id */
    def rst(stream: Int): Http2StreamException = rst(stream, name)

    /** Create a Http2Exception with the requisite stream id */
    def rst(stream: Int, msg: String): Http2StreamException =
      Http2StreamException(stream, code, msg)

    /** Extract the optional stream id and the exception message */
    def unapply(ex: Http2Exception): Option[(Option[Int], String)] =
      if (ex.code == code) {
        val stream = ex match {
          case ex: Http2StreamException => Some(ex.stream)
          case _ => None
        }
        Some(stream -> ex.getMessage)
      } else None

    def unapply(code: Int): Boolean = code == this.code

    override val toString: String =
      s"$name(0x${Integer.toHexString(code.toInt)})"
  }

  def errorGenerator(code: Long): ErrorGenerator =
    exceptionsMap.get(code) match {
      case Some(gen) => gen
      case None =>
        new ErrorGenerator(code, s"UNKNOWN(0x${Integer.toHexString(code.toInt)})")
    }

  /** Get the name associated with the error code */
  def errorName(code: Long): String = errorGenerator(code).name

  private[this] val exceptionsMap = new mutable.HashMap[Long, ErrorGenerator]()

  private def mkErrorGen(code: Long, name: String): ErrorGenerator = {
    val g = new ErrorGenerator(code, name)
    exceptionsMap += ((code, g))
    g
  }

  val NO_ERROR = mkErrorGen(0x0, "NO_ERROR")
  val PROTOCOL_ERROR = mkErrorGen(0x1, "PROTOCOL_ERROR")
  val INTERNAL_ERROR = mkErrorGen(0x2, "INTERNAL_ERROR")
  val FLOW_CONTROL_ERROR = mkErrorGen(0x3, "FLOW_CONTROL_ERROR")
  val SETTINGS_TIMEOUT = mkErrorGen(0x4, "SETTINGS_TIMEOUT")
  val STREAM_CLOSED = mkErrorGen(0x5, "STREAM_CLOSED")
  val FRAME_SIZE_ERROR = mkErrorGen(0x6, "FRAME_SIZE_ERROR")
  val REFUSED_STREAM = mkErrorGen(0x7, "REFUSED_STREAM")
  val CANCEL = mkErrorGen(0x8, "CANCEL")
  val COMPRESSION_ERROR = mkErrorGen(0x9, "COMPRESSION_ERROR")
  val CONNECT_ERROR = mkErrorGen(0xa, "CONNECT_ERROR")
  val ENHANCE_YOUR_CALM = mkErrorGen(0xb, "ENHANCE_YOUR_CALM")
  val INADEQUATE_SECURITY = mkErrorGen(0xc, "INADEQUATE_SECURITY")
  val HTTP_1_1_REQUIRED = mkErrorGen(0xd, "HTTP_1_1_REQUIRED")
}
