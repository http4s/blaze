/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.http

/** Standard HTTP request
  *
  * @param method
  *   HTTP request method
  * @param url
  *   request url
  * @param headers
  *   request headers
  * @param body
  *   function which returns the next chunk of the request body. Termination is signaled by an
  *   __empty__ `ByteBuffer` as determined by `ByteBuffer.hasRemaining()`.
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
    * @note
    *   it is generally a security flaw to log headers as they may contain sensitive user data. As
    *   such, this method should be used sparingly and almost never in a production environment.
    */
  def sensitiveToString: String = formatStr(headers.toString)
}
