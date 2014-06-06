package org.http4s.blaze.http.websocket

import javax.xml.bind.DatatypeConverter.{parseBase64Binary, printBase64Binary}
import java.nio.charset.StandardCharsets.US_ASCII
import java.security.MessageDigest

/**
 * @author Bryce Anderson
 *         Created on 1/18/14
 */
object ServerHandshaker {

  def handshakeHeaders(headers: TraversableOnce[(String, String)]): Either[(Int, String), Seq[(String, String)]] = {
    if (!headers.exists { case (k, v) => k.equalsIgnoreCase("Host")}) {
      Left(-1, "Missing Host Header")
    } else if (!headers.exists { case (k, v) => k.equalsIgnoreCase("Connection") && valueContains("Upgrade", v)}) {
      Left(-1, "Bad Connection header")
    } else if (!headers.exists { case (k, v) => k.equalsIgnoreCase("Upgrade") && v.equalsIgnoreCase("websocket") }) {
      Left(-1, "Bad Upgrade header")
    } else if (!headers.exists { case (k, v) => k.equalsIgnoreCase("Sec-WebSocket-Version") && valueContains("13", v) }) {
      Left(-1, "Bad Websocket Version header")
    } // we are past most of the 'just need them' headers
    else headers.find{case (k, v) =>
      k.equalsIgnoreCase("Sec-WebSocket-Key") && keyLength(v) == 16
    }.map { case (_, v) =>
      val respHeaders = Seq(("Upgrade", "websocket"),
                            ("Connection", "Upgrade"),
                            ("Sec-WebSocket-Accept", genAcceptKey(v)))

      Right(respHeaders)
    }.getOrElse(Left(-1, "Bad Sec-WebSocket-Key header"))
  }

  def isWebSocketRequest(headers: TraversableOnce[(String, String)]): Boolean = {
    headers.exists{ case (k, v) => k.equalsIgnoreCase("Upgrade") && v.equalsIgnoreCase("websocket") }
  }

  private def keyLength(key: String): Int = parseBase64Binary(key).length

  private def genAcceptKey(str: String): String = {
    val cript = MessageDigest.getInstance("SHA-1")
    cript.reset()
    cript.update(str.getBytes(US_ASCII))
    cript.update(magicString)
    val bytes = cript.digest()
    printBase64Binary(bytes)
  }

  private[websocket] def valueContains(key: String, value: String): Boolean = {
    val parts = value.split(",").map(_.trim)
    parts.foldLeft(false)( (b,s) => b || {
      s.equalsIgnoreCase(key) ||
        s.length > 1 &&
          s.startsWith("\"") &&
          s.endsWith("\"") &&
          s.substring(1, s.length - 1).equalsIgnoreCase(key)
    })
  }

  private[ServerHandshaker] val magicString = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(US_ASCII)
}
