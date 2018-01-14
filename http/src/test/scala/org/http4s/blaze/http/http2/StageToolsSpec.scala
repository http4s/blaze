package org.http4s.blaze.http.http2

import org.specs2.mutable.Specification


class StageToolsSpec extends Specification {

  "StageTools.validHeaderName" should {
    "Match a valid header name" in {
      StageTools.validHeaderName("abc") must_== true
    }

    "Fail a zero length name" in {
      StageTools.validHeaderName("") must_== false
    }

    "Fail a pseudo header" in {
      StageTools.validHeaderName(":method") must_== false
    }

    "Fail a header with a capital letter" in {
      StageTools.validHeaderName("Date") must_== false
    }

    "Fail a header with an invalid char" in {
      StageTools.validHeaderName("Date@") must_== false
    }

    "Accept a header with numbers" in {
      StageTools.validHeaderName('0' to '9' mkString) must_== true
    }

    "Accept a header with lower case letters" in {
      StageTools.validHeaderName('a' to 'z' mkString) must_== true
    }

    "Accept a header with non-delimiters" in {
      StageTools.validHeaderName("!#$%&'*+-.^_`|~") must_== true
    }
  }

}
