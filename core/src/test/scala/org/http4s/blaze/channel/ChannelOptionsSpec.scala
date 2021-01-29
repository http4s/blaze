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
      ch.setOption(any, any).returns(ch)

      options.applyToChannel(ch)

      there.was(
        one(ch).setOption(java.net.StandardSocketOptions.TCP_NODELAY, java.lang.Boolean.TRUE))
      there.was(
        one(ch).setOption(java.net.StandardSocketOptions.SO_KEEPALIVE, java.lang.Boolean.FALSE))
    }
  }
}
