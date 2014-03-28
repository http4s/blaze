package org.http4s.blaze.pipeline.stages.http.websocket

/**
 * @author Bryce Anderson
 *         Created on 1/15/14
 */
object WebsocketBits {

  // Masks for extracting fields
  val OP_CODE = 0xf
  val FINISHED = 0x80
  val MASK = 0x80
  val LENGTH = 0x7f
  val RESERVED = 0xe

  // message op codes
  val CONTINUATION = 0x0
  val TEXT = 0x1
  val BINARY = 0x2
  val CLOSE = 0x8
  val PING = 0x9
  val PONG = 0xa


}
