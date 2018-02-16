package org.http4s.blaze.http.http2

import java.util.Locale

import scala.annotation.tailrec
import scala.collection.generic.Growable
import scala.collection.BitSet

private object StageTools {

  /** Copy HTTP headers from `source` to dest
    *
    * The keys of `source` are converted to lower case to conform with the HTTP/2 spec.
    * https://tools.ietf.org/html/rfc7540#section-8.1.2
    */
  def copyHeaders(source: Iterable[(String, String)], dest: Growable[(String, String)]): Unit =
    source.foreach {
      case p @ (k, v) =>
        val lowerKey = k.toLowerCase(Locale.ENGLISH)
        if (lowerKey eq k) dest += p // don't need to make a new Tuple2
        else dest += lowerKey -> v
    }

  /** Check of the header name is a valid http2 header name.
    * Pseudo headers are considered invalid and should be screened before hand.
    */
  def validHeaderName(str: String): Boolean = {
    val s = str.length()
    @tailrec
    def go(i: Int): Boolean =
      if (i < s) validChars(str.charAt(i).asInstanceOf[Int]) && go(i + 1)
      else true

    s > 0 && go(0)
  }

  /* HTTP2 Spec, 8.1.2 HTTP Header Fields

     Just as in HTTP/1.x, header field names are strings of ASCII characters that are compared
     in a case-insensitive fashion. However, header field names MUST be converted to lowercase
     prior to their encoding in HTTP/2. A request or response containing uppercase header
     field names MUST be treated as malformed (Section 8.1.2.6).
     https://tools.ietf.org/html/rfc7540#section-8.1.2

     HTTP/1.1 def: https://tools.ietf.org/html/rfc7230
     header-field   = field-name ":" OWS field-value OWS
     field-name     = token
     token          = 1*tchar
     tchar          = "!" / "#" / "$" / "%" / "&" / "'" / "*"
                    / "+" / "-" / "." / "^" / "_" / "`" / "|" / "~"
                    / DIGIT / ALPHA
                    ; any VCHAR, except delimiters

   */
  private val validChars = BitSet(
    (('0' to '9') ++ ('a' to 'z') ++ "!#$%&'*+-.^_`|~").map(_.toInt): _*)
}
