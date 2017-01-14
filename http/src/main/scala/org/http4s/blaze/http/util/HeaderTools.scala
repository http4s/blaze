package org.http4s.blaze.http.util

private[blaze] object HeaderTools {

  case class SpecialHeaders(
      transferEncoding: Option[String],
      contentLength: Option[String],
      connection: Option[String])

  def isKeepAlive(headerValue: String, minorVersion: Int): Boolean = {
    if (headerValue.equalsIgnoreCase("keep-alive")) true
    else if (headerValue.equalsIgnoreCase("close")) false
    else if (headerValue.equalsIgnoreCase("upgrade")) true
    else false
  }

  /**
    * Reader the headers to the `StringBuilder` with the exception of Transfer-Encoding and
    * Content-Length headers, which are returned.
    */
  def renderHeaders[H: HeaderLike](sb: StringBuilder, headers: Iterable[H]): SpecialHeaders = {
    // We watch for some headers that are important to the HTTP protocol
    var transferEncoding: Option[String] = None
    var contentLength: Option[String] = None
    var connection: Option[String] = None

    val hl = HeaderLike[H]
    val it = headers.iterator

    while (it.hasNext) {
      val header = it.next()
      val k = hl.getKey(header)
      val v = hl.getValue(header)

      // We are not allowing chunked responses at the moment, strip our Chunked-Encoding headers
      if (k.equalsIgnoreCase(HeaderNames.TransferEncoding)) {
        transferEncoding = Some(v)
      } else if (k.equalsIgnoreCase(HeaderNames.ContentLength)) {
        contentLength = Some(v)
      } else if (k.equalsIgnoreCase(HeaderNames.Connection)) {
        connection = Some(v)
      } else {
        sb.append(k)
        if (v.length > 0) sb.append(": ").append(v)
        sb.append("\r\n")
      }
    }

    SpecialHeaders(
      transferEncoding,
      contentLength,connection
    )
  }
}
