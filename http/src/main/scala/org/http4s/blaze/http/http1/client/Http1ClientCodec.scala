/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http1.client

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http._
import org.http4s.blaze.http.parser.Http1ClientParser
import org.http4s.blaze.util.BufferTools

private final class Http1ClientCodec(config: HttpClientConfig)
    extends Http1ClientParser(
      config.maxResponseLineLength,
      config.maxHeadersLength,
      1024,
      Int.MaxValue,
      config.lenientParser) {
  import Http1ClientCodec._

  private[this] val headers = Vector.newBuilder[(String, String)]
  private[this] var code = -1
  private[this] var reason = ""
  private[this] var scheme = ""
  private[this] var majorVersion = -1
  private[this] var minorVersion = -1

  protected def submitResponseLine(
      code: Int,
      reason: String,
      scheme: String,
      majorversion: Int,
      minorversion: Int): Unit = {
    this.code = code
    this.reason = reason
    this.scheme = scheme
    this.majorVersion = majorversion
    this.minorVersion = minorversion
  }

  protected def headerComplete(name: String, value: String): Boolean = {
    headers += name -> value
    false
  }

  override def reset(): Unit = {
    super.reset()
    // Internal reset
    headers.clear()
    code = -1
    reason = ""
    scheme = ""
    majorVersion = -1
    minorVersion = -1
  }

  def getResponsePrelude: HttpResponsePrelude =
    HttpResponsePrelude(this.code, this.reason, headers.result())

  def preludeComplete(): Boolean = headersComplete

  /** Parse the response prelude, which consists of the repsonse line and headers
    *
    * Returns `true` once the prelude is complete.
    */
  def parsePrelude(buffer: ByteBuffer): Boolean =
    if (!responseLineComplete && !parseResponseLine(buffer)) false
    else if (!headersComplete && !parseHeaders(buffer)) false
    else true

  /** Parse the body of the response
    *
    * If The content is complete or more input is required, an empty buffer is returned.
    */
  def parseData(buffer: ByteBuffer): ByteBuffer =
    if (contentComplete()) BufferTools.emptyBuffer
    else
      parseContent(buffer) match {
        case null => BufferTools.emptyBuffer
        case other => other
      }

  /** Encode the request prelude into a `ByteBuffer`
    *
    * @note This method attempts to add a `Host` header, if necessary and if
    *       a body is present it will add a `Transfer-Encoding: chunked` if
    *       it is not already present and a `Content-Length` header isn't detected.
    */
  def encodeRequestPrelude(request: HttpRequest, hasBody: Boolean): EncodedPrelude = {
    // If this is a HEAD request we must not get a body back even though
    // the headers etc may suggest otherwise, so let the parser know.
    isHeadRequest(request.method == "HEAD")

    val uri = java.net.URI.create(request.url)
    val sb = new StringBuilder(256)

    sb.append(request.method).append(' ')
    appendPath(sb, uri)
    sb.append(' ')
      .append("HTTP/")
      .append(request.majorVersion)
      .append('.')
      .append(request.minorVersion)
      .append("\r\n")
    val requiresHost = !(request.majorVersion == 1 && request.minorVersion == 0)
    val isChunked =
      appendHeaders(sb, uri, request.headers, hasBody, requiresHost)
    sb.append("\r\n")

    val data =
      ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.ISO_8859_1))
    val encoder =
      if (isChunked) Http1BodyEncoder.ChunkedTransferEncoder
      else Http1BodyEncoder.IdentityEncoder

    EncodedPrelude(data, encoder)
  }
}

private object Http1ClientCodec {
  case class EncodedPrelude(data: ByteBuffer, encoder: Http1BodyEncoder)

  private def appendPath(sb: StringBuilder, uri: java.net.URI): Unit = {
    val path = valueOrDefault(uri.getRawPath, "/")
    val queryString = valueOrDefault(uri.getRawQuery, "")

    sb.append(path)

    if (!queryString.isEmpty) {
      sb.append("?").append(queryString)
      ()
    }
  }

  // Returns `true` if using chunked-transfer encoding, `false` otherwise.
  private def appendHeaders(
      sb: StringBuilder,
      uri: java.net.URI,
      hs: Headers,
      hasBody: Boolean,
      requireHost: Boolean
  ): Boolean = {
    var hasHostHeader: Boolean = false
    var hasContentLength: Boolean = false
    var hasChunkedEncoding: Boolean = false

    hs.foreach { case (k, v) =>
      if (requireHost && !hasHostHeader && k.equalsIgnoreCase("Host"))
        hasHostHeader = true
      if (hasBody && !hasContentLength && k.equalsIgnoreCase("Content-Length"))
        hasContentLength = true
      if (hasBody && !hasChunkedEncoding &&
        k.equalsIgnoreCase("Transfer-Encoding") && v == "chunked")
        hasChunkedEncoding = true
      sb.append(k)
        .append(':')
        .append(v)
        .append("\r\n")
    }

    if (requireHost && !hasHostHeader)
      sb.append("Host:")
        .append(uri.getAuthority)
        .append("\r\n")

    if (hasChunkedEncoding) true
    else if (hasContentLength || !hasBody) false
    else {
      sb.append("Transfer-Encoding:chunked\r\n")
      true
    }
  }

  private def valueOrDefault(str: String, default: String): String =
    str match {
      case "" | null => default
      case path => path
    }
}
