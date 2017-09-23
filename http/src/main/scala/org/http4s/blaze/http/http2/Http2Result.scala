package org.http4s.blaze.http.http2

/** Result type of many of the codec methods */
sealed trait Http2Result extends Product with Serializable
case object Halt extends Http2Result
case object BufferUnderflow extends Http2Result

/** Represents the possibility of failure */
sealed abstract class MaybeError extends Http2Result {
  final def success: Boolean = this == Continue
}

case object Continue extends MaybeError
final case class Error(err: Http2Exception) extends MaybeError
