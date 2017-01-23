package org.http4s.blaze.http.http20

import org.http4s.blaze.http.util.HeaderNames

import scala.annotation.tailrec
import scala.collection.BitSet


object Http2StageTools {
  // Request pseudo headers
  val Method = ":method"
  val Scheme = ":scheme"
  val Path   = ":path"
  val Authority = ":authority"

  // Response pseudo header
  val Status = ":status"
  val TE = "te"
  val Connection = HeaderNames.Connection
  val ContentLength = HeaderNames.ContentLength

  /** Check of the header name is a valid http2 header name.
    * Pseudo headers are considered invalid and should be screened before hand.
    */
  def validHeaderName(str: String): Boolean = {
    val s = str.length()
    @tailrec
    def go(i: Int): Boolean = {
      if (i < s) validChars(str.charAt(i).asInstanceOf[Int]) && go (i + 1)
      else true
    }

    s > 0 && go(0)
  }

  /* HTTP2 Spec, 8.1.2 HTTP Header Fields  : http://http2.github.io/http2-spec/index.html#HttpHeaders

    "Just as in HTTP/1.x, header field names are strings of ASCII characters that are compared
     in a case-insensitive fashion. However, header field names MUST be converted to lowercase
     prior to their encoding in HTTP/2. A request or response containing uppercase header
     field names MUST be treated as malformed (Section 8.1.2.6). "


    HTTP/1.1 def: https://tools.ietf.org/html/rfc7230
     header-field   = field-name ":" OWS field-value OWS
     field-name     = token
     token          = 1*tchar
     tchar          = "!" / "#" / "$" / "%" / "&" / "'" / "*"
                    / "+" / "-" / "." / "^" / "_" / "`" / "|" / "~"
                    / DIGIT / ALPHA
                    ; any VCHAR, except delimiters

   */

  private val validChars = BitSet((('0' to '9') ++ ('a' to 'z') ++ "!#$%&'*+-.^_`|~") map (_.toInt):_*)
}
