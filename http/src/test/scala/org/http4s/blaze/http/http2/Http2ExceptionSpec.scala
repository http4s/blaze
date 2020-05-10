/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2

import org.specs2.mutable.Specification

class Http2ExceptionSpec extends Specification {
  import Http2Exception._

  "Http2Exception" should {
    "be a connection error for stream id 0" in {
      PROTOCOL_ERROR.goaway("") must beAnInstanceOf[Http2SessionException]
      PROTOCOL_ERROR.rst(1, "") must beAnInstanceOf[Http2StreamException]
    }
  }
}
