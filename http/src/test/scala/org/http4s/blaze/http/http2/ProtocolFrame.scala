package org.http4s.blaze.http.http2

// TODO: these may form the basis of what gets written to the WriteListener
sealed abstract class ProtocolFrame private extends Product with Serializable

object ProtocolFrame {
  case class GoAway(lastHandleStream: Int, cause: Http2Exception) extends ProtocolFrame

  case object Empty extends ProtocolFrame
}