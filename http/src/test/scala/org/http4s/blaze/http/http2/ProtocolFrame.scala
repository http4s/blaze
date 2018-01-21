package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.Http2Settings.Setting

// TODO: these may form the basis of what gets written to the WriteListener
sealed abstract class ProtocolFrame private extends Product with Serializable

object ProtocolFrame {
  case class GoAway(lastHandleStream: Int, cause: Http2Exception) extends ProtocolFrame

  case class Ping(isAck: Boolean, data: Array[Byte]) extends ProtocolFrame

  case class Settings(settings: Option[Seq[Setting]]) extends ProtocolFrame

  case class Settings(settings: Option[Seq[Setting]]) extends ProtocolFrame

  case object Empty extends ProtocolFrame
}