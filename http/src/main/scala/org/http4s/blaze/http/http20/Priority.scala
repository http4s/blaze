package org.http4s.blaze.http.http20

/** object representing the contents of a PRIORITY frame
  *
  * This is also used for the HEADERS frame which is logically
  * a series of headers with a possible PRIORITY frame
  */
case class Priority(dependentStreamId: Int, exclusive: Boolean, priority: Int) {
  require(dependentStreamId >= 0, "Invalid stream dependency")
  require(priority > 0 && priority <= 256, "Weight must be 1 to 256")
}
