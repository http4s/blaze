package org.http4s.blaze.http.http2

import org.specs2.mutable.Specification

class HeaderEncoderSpec extends Specification {
  private val headers = Seq("foo" -> "bar")
  "HeaderEncoder" should {
    "encode headers" in {
      val enc = new HeaderEncoder(Int.MaxValue)
      val bb = enc.encodeHeaders(headers)

      HeaderCodecHelpers.decodeHeaders(bb, Int.MaxValue) must_== headers
    }
  }
}
