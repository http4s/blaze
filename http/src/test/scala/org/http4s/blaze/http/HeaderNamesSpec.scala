/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http

import org.specs2.mutable.Specification

class HeaderNamesSpec extends Specification {
  "HeaderNames.validH2HeaderKey" should {
    "Match a valid header name" in {
      HeaderNames.validH2HeaderKey("abc") must_== true
    }

    "Fail a zero length name" in {
      HeaderNames.validH2HeaderKey("") must_== false
    }

    "Fail a pseudo header" in {
      HeaderNames.validH2HeaderKey(":method") must_== false
    }

    "Fail a header with a capital letter" in {
      HeaderNames.validH2HeaderKey("Date") must_== false
    }

    "Fail a header with an invalid char" in {
      HeaderNames.validH2HeaderKey("Date@") must_== false
    }

    "Accept a header with numbers" in {
      HeaderNames.validH2HeaderKey('0' to '9' mkString) must_== true
    }

    "Accept a header with lower case letters" in {
      HeaderNames.validH2HeaderKey('a' to 'z' mkString) must_== true
    }

    "Accept a header with non-delimiters" in {
      HeaderNames.validH2HeaderKey("!#$%&'*+-.^_`|~") must_== true
    }
  }
}
