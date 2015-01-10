package org.http4s.blaze.http

package object http20 {

  /** Result type of many of the codec methods */
  sealed trait Http2Result
  case object Halt extends Http2Result
  case object BufferUnderflow extends Http2Result

  /** Represents the possibility of failure */
  sealed trait MaybeError extends Http2Result              { def          success: Boolean }
  case object Continue extends MaybeError                  { override def success: Boolean = true }
  case class Error(err: Http2Exception) extends MaybeError { override def success: Boolean = false }

  //////////////////////////////////////////////////

  /** object representing the contents of a PRIORITY frame
    *
    * This is also used for the HEADERS frame which is logically
    * a series of headers with a possible PRIORITY frame
    */
  case class Priority(dependentStreamId: Int, exclusive: Boolean, priority: Int) {
    require(dependentStreamId >= 0, "Invalid stream dependency")
    require(priority > 0 && priority <= 256, "Weight must be 1 to 256")
  }
}
