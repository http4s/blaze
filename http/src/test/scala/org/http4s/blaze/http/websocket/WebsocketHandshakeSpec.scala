package org.http4s.blaze.http.websocket

import org.specs2.mutable.Specification

class WebsocketHandshakeSpec extends Specification {

  "WebsocketHandshake" should {

    "Be able to split multi value header keys" in {
      val totalValue = "keep-alive, Upgrade"
      val values = List("upgrade",  "Upgrade", "keep-alive", "Keep-alive")
      values.foldLeft(true){ (b, v) =>
        b && ServerHandshaker.valueContains(v, totalValue)
      } should_== true
    }

  }

}
