/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http

/** Standard HTTP request
  *
  * @param method HTTP request method
  * @param url request url
  * @param headers request headers
  * @param body function which returns the next chunk of the request body. Termination is
  *             signaled by an __empty__ `ByteBuffer` as determined by `ByteBuffer.hasRemaining()`.
  */
case class HttpRequest(
    method: Method,
    url: Url,
    majorVersion: Int,
    minorVersion: Int,
    headers: Headers,
    body: BodyReader
) {
  private[this] def formatStr(headersString: String): String =
    s"HttpRequest($method, $url, $majorVersion, $minorVersion, $headersString, $body)"

  override def toString: String =
    if (logsensitiveinfo()) sensitiveToString
    else formatStr(headers.toString)

  /** A String representation of this request that includes the headers
    *
    * @note it is generally a security flaw to log headers as they may contain
    *       sensitive user data. As such, this method should be used sparingly
    *       and almost never in a production environment.
    */
  def sensitiveToString: String = formatStr(headers.toString)
}
