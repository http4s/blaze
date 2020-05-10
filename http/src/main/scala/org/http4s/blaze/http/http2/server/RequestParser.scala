/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2.server

import org.http4s.blaze.http.{BodyReader, HttpRequest, _}
import org.http4s.blaze.http.http2.PseudoHeaders._

import scala.collection.mutable.ArrayBuffer

/** Tool for turning a HEADERS frame into a request */
private[server] object RequestParser {
  def makeRequest(hs: Headers, body: BodyReader): Either[String, HttpRequest] = {
    val normalHeaders =
      new ArrayBuffer[(String, String)](math.max(0, hs.size - 3))
    var method: String = null
    var scheme: String = null
    var path: String = null
    var error: String = ""
    var pseudoDone = false

    hs.foreach {
      case (k, v) if isPseudo(k) =>
        if (pseudoDone) error += s"Pseudo header ($k) in invalid position. "
        else
          k match {
            case Method =>
              if (method == null) method = v
              else error += "Multiple ':method' headers defined. "

            case Scheme =>
              if (scheme == null) scheme = v
              else error += "Multiple ':scheme' headers defined. "

            case Path =>
              if (path == null) {
                if (v.isEmpty)
                  error += "Received :path pseudo-header with empty value. "
                path = v
              } else error += "Multiple ':path' headers defined. "

            case Authority => // NOOP; TODO: maybe we should synthesize a Host header out of this?

            case _ =>
              // Endpoints MUST treat a request or response that contains
              // undefined or invalid pseudo-header fields as malformed
              // https://tools.ietf.org/html/rfc7540#section-8.1.2.1
              error += s"Invalid pseudo header: $k. "
          }

      case h @ (k, v) => // Non pseudo headers
        pseudoDone = true
        k match {
          case HeaderNames.Connection =>
            error += s"HTTP/2.0 forbids connection specific headers: $h. "

          case HeaderNames.TE =>
            if (!v.equalsIgnoreCase("trailers"))
              error += "HTTP/2.0 forbids TE header values other than 'trailers'. "
          // ignore otherwise

          case _ if !HeaderNames.validH2HeaderKey(k) =>
            error += s"Invalid header key: $k. "

          case _ =>
            normalHeaders += h
        }
    }

    // All HTTP/2 requests MUST include exactly one valid value for the
    // ":method", ":scheme", and ":path" pseudo-header fields, unless it is
    // a CONNECT request (Section 8.3).  An HTTP request that omits
    // mandatory pseudo-header fields is malformed (Section 8.1.2.6).
    // https://tools.ietf.org/html/rfc7540#section-8.1.2.3
    if (method == null || scheme == null || path == null) {
      error += s"Invalid request: missing pseudo headers. Method: $method, Scheme: $scheme, path: $path. "
    }

    if (0 < error.length) Left(error)
    else Right(HttpRequest(method, path, 2, 0, normalHeaders, body))
  }

  private[this] def isPseudo(key: String): Boolean =
    0 < key.length && key.charAt(0) == ':'
}
