package org.http4s.websocket

import org.specs2.mutable.Specification


class WebsocketHandshakeSpec extends Specification {

  "WebsocketHandshake" should {

    "Be able to split multi value header keys" in {
      val totalValue = "keep-alive, Upgrade"
      val values = List("upgrade",  "Upgrade", "keep-alive", "Keep-alive")
      values.foldLeft(true){ (b, v) =>
        b && WebsocketHandshake.valueContains(v, totalValue)
      } should_== true
    }

    "Do a round trip" in {
      val client = WebsocketHandshake.clientHandshaker("www.foo.com")
      val valid = WebsocketHandshake.serverHandshake(client.initHeaders)
      valid must beRight

      val Right(headers) = valid
      client.checkResponse(headers) must beRight
    }

  }

}
