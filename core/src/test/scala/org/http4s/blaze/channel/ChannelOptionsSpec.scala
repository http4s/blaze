package org.http4s.blaze.channel

import java.nio.channels.NetworkChannel

import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

class ChannelOptionsSpec extends Specification with Mockito {

  "ChannelOptions" should {
    "be set on a NetworkChannel" in {
      val options = ChannelOptions(
        OptionValue[java.lang.Boolean](java.net.StandardSocketOptions.TCP_NODELAY, true),
        OptionValue[java.lang.Boolean](java.net.StandardSocketOptions.SO_KEEPALIVE, false)
      )

      val ch = mock[NetworkChannel]
      ch.setOption(any, any) returns ch

      options.applyToChannel(ch)

      there was one(ch).setOption(java.net.StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE)
      there was one(ch).setOption(java.net.StandardSocketOptions.SO_KEEPALIVE, java.lang.Boolean.FALSE)
    }
  }
}
