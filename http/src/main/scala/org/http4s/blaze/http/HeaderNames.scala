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

import scala.annotation.tailrec
import scala.collection.BitSet

/** Incomplete collection of header names, all lower case for easy compatibility with HTTP/2.
  */
object HeaderNames {
  val Connection = "connection"
  val ContentLength = "content-length"
  val ContentType = "content-type"
  val Date = "date"
  val TE = "te"
  val TransferEncoding = "transfer-encoding"

  /** Check of the header key is a valid HTTP/2 header key. Pseudo headers are considered invalid
    * and should be screened before hand.
    */
  def validH2HeaderKey(str: String): Boolean = {
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
