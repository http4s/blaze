package blaze.pipeline.stages.http.websocket

/**
 * @author Bryce Anderson
 *         Created on 1/15/14
 */


sealed trait WebSocMessage {
  def finished: Boolean
}

case class Continuation(msg: Array[Byte], finished: Boolean) extends WebSocMessage
case class BinaryMessage(msg: Array[Byte], finished: Boolean) extends WebSocMessage
case class TextMessage(msg: String, finished: Boolean) extends WebSocMessage
case class Ping(msg: Array[Byte], finished: Boolean) extends WebSocMessage
case class Pong(msg: Array[Byte], finished: Boolean) extends WebSocMessage
case class Close(msg: Array[Byte], finished: Boolean) extends WebSocMessage


