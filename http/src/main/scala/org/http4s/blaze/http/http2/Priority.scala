package org.http4s.blaze.http.http2

sealed trait Priority {
  def isDefined: Boolean
}

object Priority {
  /** object representing the contents of a PRIORITY frame
    *
    * This is also used for the HEADERS frame which is logically
    * a series of headers with a possible PRIORITY frame
    */
  final case class Dependent(dependentStreamId: Int, exclusive: Boolean, priority: Int)
      extends Priority {
    require(0 <= dependentStreamId, "Invalid stream dependency")
    require(0 < priority && priority <= 256, "Weight must be 1 to 256")

    def isDefined: Boolean = true
  }

  /** Represents a lack of priority */
  case object NoPriority extends Priority {
    def isDefined: Boolean = false
  }
}
