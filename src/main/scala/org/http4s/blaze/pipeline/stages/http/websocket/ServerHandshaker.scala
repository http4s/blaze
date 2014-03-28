package org.http4s.blaze.pipeline.stages.http.websocket

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
    } else if (!headers.exists { case (k, v) => k.equalsIgnoreCase("Connection") && v.equalsIgnoreCase("Upgrade")}) {
      Left(-1, "Bad Connection header")
    }else if (!headers.exists { case (k, v) => k.equalsIgnoreCase("Upgrade") && v.equalsIgnoreCase("websocket") }) {
      Left(-1, "Bad Upgrade header")
    } else if (!headers.exists { case (k, v) => k.equalsIgnoreCase("Sec-WebSocket-Version") && v == "13" }) {
      Left(-1, "Bad Websocket Version header")
    } else headers.find{case (k, v) =>
      k.equalsIgnoreCase("Sec-WebSocket-Key") && keyLength(v) == 16
    }.map { case (_, v) =>
      Right(("Upgrade", "websocket")::("Connection", "Upgrade")::("Sec-WebSocket-Accept", genAcceptKey(v))::Nil)
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

  private[ServerHandshaker] val magicString = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(US_ASCII)
}
