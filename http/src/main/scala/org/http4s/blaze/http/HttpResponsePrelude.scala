/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http

/** The prelude of a standard HTTP response
  *
  * @param code Response status code
  * @param status Response message. This has no meaning for the protocol, its purely for human enjoyment.
  * @param headers Response headers.
  */
case class HttpResponsePrelude(code: Int, status: String, headers: Headers) {
  private[this] def formatStr(headersString: String): String =
    s"HttpResponsePrelude($code, $status, $headersString)"

  override def toString: String =
    if (logsensitiveinfo()) sensitiveToString
    else formatStr("<headers hidden>")

  /** A String representation of this response prelude that includes the headers
    *
    * @note it is generally a security flaw to log headers as they may contain
    *       sensitive user data. As such, this method should be used sparingly
    *       and almost never in a production environment.
    */
  def sensitiveToString: String = formatStr(headers.toString)
}
