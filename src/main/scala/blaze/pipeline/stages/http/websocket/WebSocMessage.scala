//package blaze.pipeline.stages.http.websocket
//
///**
// * @author Bryce Anderson
// *         Created on 1/15/14
// */
//
//
//sealed trait WebSocMessage {
//  def finished: Boolean
//  def data: Array[Byte]
//  def length: Int = data.length
//}
//
//case class Continuation(data: Array[Byte], finished: Boolean) extends WebSocMessage
//case class BinaryMessage(data: Array[Byte], finished: Boolean) extends WebSocMessage
//case class Ping(data: Array[Byte], finished: Boolean) extends WebSocMessage
//case class Pong(data: Array[Byte], finished: Boolean) extends WebSocMessage
//case class Close(data: Array[Byte], finished: Boolean) extends WebSocMessage
//case class TextMessage(data: Array[Byte], finished: Boolean) extends WebSocMessage
//
