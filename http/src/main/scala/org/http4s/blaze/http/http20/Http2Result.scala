package org.http4s.blaze.http.http20

/** Result type of many of the codec methods */
sealed trait Http2Result
case object Halt extends Http2Result
case object BufferUnderflow extends Http2Result

/** Represents the possibility of failure */
sealed trait MaybeError extends Http2Result              { def          success: Boolean }
case object Continue extends MaybeError                  { override def success: Boolean = true }
case class Error(err: Http2Exception) extends MaybeError { override def success: Boolean = false }