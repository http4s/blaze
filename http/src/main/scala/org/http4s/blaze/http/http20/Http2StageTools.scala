package org.http4s.blaze.http.http20

import scala.annotation.tailrec


object Http2StageTools {
  // Request pseudo headers
  val Method = ":method"
  val Scheme = ":scheme"
  val Path   = ":path"
  val Authority = ":authority"

  // Response pseudo header
  val Status = ":status"
  val Connection = "connection"
  val TE = "te"
  val ContentLength = "content-length"

  def isLowerCase(str: String): Boolean = {
    @tailrec
    def go(i: Int): Boolean = {
      if (i == str.length) true
      else {
        val ch = str.charAt(i)
        if ('A' <= ch && ch <= 'Z') false
        else go(i + 1)
      }
    }
    go(0)
  }
}
