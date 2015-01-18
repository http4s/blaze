package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8

import scala.collection.mutable

final case class Http2Exception(code: Int, name: String, stream: Option[Int], fatal: Boolean)(val msg: String)
  extends Exception(msg)
{
  def msgBuffer(): ByteBuffer = ByteBuffer.wrap(msg.getBytes(UTF_8))
}

///////////////////// HTTP/2.0 Errors  /////////////////////////////
object Http2Exception {

  def get(id: Int): String = exceptionsMap.get(id)
                               .map(_.name)
                               .getOrElse(s"UNKNOWN_ERROR(0x${Integer.toHexString(id)}")

  final class ErrorGen private[http20](val code: Int, val name: String) {
    def apply(fatal: Boolean): Http2Exception = Http2Exception(code, name, None, fatal)(name)
    def apply(msg: String, fatal: Boolean): Http2Exception = Http2Exception(code, name, None, fatal)(name + ": " + msg)
    def apply(stream: Int, fatal: Boolean): Http2Exception = Http2Exception(code, name, Some(stream), fatal)(name)
    def apply(msg: String, stream: Int, fatal: Boolean): Http2Exception = Http2Exception(code, name, Some(stream), fatal)(msg)

    def unapply(ex: Http2Exception): Option[(String, Option[Int])] = {
      if (ex.code == code) Some(( ex.msg, ex.stream))
      else None
    }

    def unapply(code: Int): Option[Unit] = {
      if (code == this.code) Some(()) else None
    }

    override val toString: String = s"$name(0x${Integer.toHexString(code)})"
  }

  def errorName(code: Int): String = exceptionsMap.get(code)
    .map(_.name)
    .getOrElse(s"UNKNOWN(0x${Integer.toHexString(code)}")


  private val exceptionsMap = new mutable.HashMap[Int, ErrorGen]()

  private def mkErrorGen(code: Int, name: String): ErrorGen = {
    val g = new ErrorGen(code, name)
    exceptionsMap += ((code, g))
    g
  }

  val NO_ERROR                 = mkErrorGen(0x0, "NO_ERROR")
  val PROTOCOL_ERROR           = mkErrorGen(0x1, "PROTOCOL_ERROR")
  val INTERNAL_ERROR           = mkErrorGen(0x2, "INTERNAL_ERROR")
  val FLOW_CONTROL_ERROR       = mkErrorGen(0x3, "FLOW_CONTROL_ERROR")
  val SETTINGS_TIMEOUT         = mkErrorGen(0x4, "SETTINGS_TIMEOUT")
  val STREAM_CLOSED            = mkErrorGen(0x5, "STREAM_CLOSED")
  val FRAME_SIZE_ERROR         = mkErrorGen(0x6, "FRAME_SIZE_ERROR")
  val REFUSED_STREAM           = mkErrorGen(0x7, "REFUSED_STREAM")
  val CANCEL                   = mkErrorGen(0x8, "CANCEL")
  val COMPRESSION_ERROR        = mkErrorGen(0x9, "COMPRESSION_ERROR")
  val CONNECT_ERROR            = mkErrorGen(0xa, "CONNECT_ERROR")
  val ENHANCE_YOUR_CALM        = mkErrorGen(0xb, "ENHANCE_YOUR_CALM")
  val INADEQUATE_SECURITY      = mkErrorGen(0xc, "INADEQUATE_SECURITY")
  val HTTP_1_1_REQUIRED        = mkErrorGen(0xd, "HTTP_1_1_REQUIRED")

}
