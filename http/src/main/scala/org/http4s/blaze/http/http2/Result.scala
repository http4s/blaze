package org.http4s.blaze.http.http2

/** Result type of many of the codec methods */
private sealed trait Result extends Product with Serializable

/** Didn't get enough data to decode a full HTTP/2 frame */
private case object BufferUnderflow extends Result

/** Represents the possibility of failure */
private sealed abstract class MaybeError extends Result {
  final def success: Boolean = this == Continue
}

private object MaybeError {
  def apply(option: Option[Http2Exception]): MaybeError = option match {
    case None => Continue
    case Some(ex) => Error(ex)
  }
}

private case object Continue extends MaybeError
private final case class Error(err: Http2Exception) extends MaybeError
