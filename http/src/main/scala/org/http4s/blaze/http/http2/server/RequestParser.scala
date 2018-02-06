package org.http4s.blaze.http.http2.server

import org.http4s.blaze.http.http2.StageTools._
import org.http4s.blaze.http.{BodyReader, HttpRequest, _}

import scala.collection.mutable.ArrayBuffer

/** Tool for turning a HEADERS frame into a request */
private object RequestParser {
  def makeRequest(hs: Headers, body: BodyReader): Either[String, HttpRequest] = {

    val normalHeaders = new ArrayBuffer[(String, String)](hs.size)
    var method: String = null
    var scheme: String = null
    var path: String = null
    var error: String = ""
    var pseudoDone = false

    hs.foreach {
      case (Method, v)    =>
        if (pseudoDone) error += "Pseudo header in invalid position. "
        else if (method == null) method = v
        else error += "Multiple ':method' headers defined. "

      case (Scheme, v)    =>
        if (pseudoDone) error += "Pseudo header in invalid position. "
        else if (scheme == null) scheme = v
        else error += "Multiple ':scheme' headers defined. "

      case (Path, v)      =>
        if (pseudoDone) error += "Pseudo header in invalid position. "
        else if (path == null)   {
          if (v.isEmpty) error += "Received :path pseudo-header with empty value. "
          path = v
        }
        else error += "Multiple ':path' headers defined. "

      case (Authority, _) => // NOOP; TODO: maybe we should synthesize a Host header out of this?
        if (pseudoDone) error += "Pseudo header in invalid position. "

      case h@(k, _) if k.startsWith(":") => error += s"Invalid pseudo header: $h. "
      case h@(k, _) if !validHeaderName(k) => error += s"Invalid header key: $k. "

      case hs =>    // Non pseudo headers
        pseudoDone = true
        hs match {
          case h@(Connection, _) => error += s"HTTP/2.0 forbids connection specific headers: $h. "

          case h@(TE, v) =>
            if (!v.equalsIgnoreCase("trailers")) error += s"HTTP/2.0 forbids TE header values other than 'trailers'. "
          // ignore otherwise

          case header => normalHeaders += header
        }
    }

    if (method == null || scheme == null || path == null) {
      error += s"Invalid request: missing pseudo headers. Method: $method, Scheme: $scheme, path: $path. "
    }

    if (0 < error.length) Left(error)
    else Right(HttpRequest(method, path, 2, 0, hs, body))
  }
}
