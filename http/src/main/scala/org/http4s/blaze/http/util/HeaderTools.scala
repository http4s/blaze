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

package org.http4s.blaze.http.util

import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import java.util.Locale

import org.http4s.blaze.http.HeaderNames

private[blaze] object HeaderTools {
  private case class CachedDateHeader(acquired: Long, header: String)

  private val dateFormat =
    DateTimeFormatter
      .ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz")
      .withLocale(Locale.US)
      .withZone(ZoneId.of("GMT"))

  @volatile
  private var dateTime = CachedDateHeader(0L, "")

  // We check to see if the current date header was generated over a second ago, and if so
  // we regenerate it. This can race but the worst case scenario is duplication of work
  private def getDateHeader(): String = {
    val cached = dateTime
    val current = System.currentTimeMillis()
    if (current - cached.acquired <= 1000) cached.header
    else {
      val next = "date: " + dateFormat.format(Instant.now()) + "\r\n"
      dateTime = CachedDateHeader(current, next)
      next
    }
  }

  case class SpecialHeaders(
      transferEncoding: Option[String],
      contentLength: Option[String],
      connection: Option[String])

  def isKeepAlive(connectionHeader: Option[String], minorVersion: Int): Boolean =
    connectionHeader match {
      case Some(headerValue) =>
        if (headerValue.equalsIgnoreCase("keep-alive")) true
        else if (headerValue.equalsIgnoreCase("close")) false
        else if (headerValue.equalsIgnoreCase("upgrade")) true
        else false
      case None => minorVersion != 0
    }

  /** Render the headers to the `StringBuilder` with the exception of Transfer-Encoding and
    * Content-Length headers, which are returned.
    */
  def renderHeaders[H: HeaderLike](sb: StringBuilder, headers: Iterable[H]): SpecialHeaders = {
    // We watch for some headers that are important to the HTTP protocol
    var transferEncoding: Option[String] = None
    var contentLength: Option[String] = None
    var connection: Option[String] = None
    var hasDateheader = false

    val hl = HeaderLike[H]
    val it = headers.iterator

    while (it.hasNext) {
      val header = it.next()
      val k = hl.getKey(header)
      val v = hl.getValue(header)

      // We are not allowing chunked responses at the moment, strip our Chunked-Encoding headers
      if (k.equalsIgnoreCase(HeaderNames.TransferEncoding))
        transferEncoding = Some(v)
      else if (k.equalsIgnoreCase(HeaderNames.ContentLength))
        contentLength = Some(v)
      else if (k.equalsIgnoreCase(HeaderNames.Connection))
        connection = Some(v)
      else {
        // Just want to know if its here, going to add it regardless
        if (!hasDateheader && k.equalsIgnoreCase(HeaderNames.Date))
          hasDateheader = true

        sb.append(k)
        if (v.length > 0) sb.append(": ").append(v)
        sb.append("\r\n")
      }
    }

    // Add the Date header, if necessary.
    if (!hasDateheader) sb.append(getDateHeader())

    SpecialHeaders(
      transferEncoding,
      contentLength,
      connection
    )
  }
}
