package org.http4s.blaze.http.http2

import org.specs2.mutable.Specification


class Http2StageToolsSpec extends Specification {

  "Http2StageTools.validHeaderName" should {
    "Match a valid header name" in {
      Http2StageTools.validHeaderName("abc") must_== true
    }

    "Fail a zero length name" in {
      Http2StageTools.validHeaderName("") must_== false
    }

    "Fail a pseudo header" in {
      Http2StageTools.validHeaderName(":method") must_== false
    }

    "Fail a header with a capitol letter" in {
      Http2StageTools.validHeaderName("Date") must_== false
    }

    "Fail a header with an invalid char" in {
      Http2StageTools.validHeaderName("Date@") must_== false
    }

    "Accept a header with numbers" in {
      Http2StageTools.validHeaderName('0' to '9' mkString) must_== true
    }

    "Accept a header with lower case letters" in {
      Http2StageTools.validHeaderName('a' to 'z' mkString) must_== true
    }

    "Accept a header with non-delimiters" in {
      Http2StageTools.validHeaderName("!#$%&'*+-.^_`|~") must_== true
    }
  }

}
