package org.http4s.blaze.http

/**
  * Incomplete collection of header names, all lower case for
  * easy compatibility with HTTP/2.
  */
object HeaderNames {
  val Connection = "connection"
  val ContentLength = "content-length"
  val ContentType = "content-type"
  val Date = "date"
  val TE = "te"
  val TransferEncoding = "transfer-encoding"

}
