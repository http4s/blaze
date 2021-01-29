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

package org.http4s.blaze.channel.nio2

import java.net.{InetSocketAddress, SocketTimeoutException}

import org.http4s.blaze.test.FastTickWheelExecutor
import org.specs2.mutable.Specification

import scala.concurrent.Await
import scala.concurrent.duration.DurationDouble

class ClientChannelFactorySpec extends Specification {
  "ClientChannelFactory" should {
    "time out" in new FastTickWheelExecutor {
      val factory =
        new ClientChannelFactory(connectTimeout = 1.millisecond, scheduler = scheduler)
      val address = new InetSocketAddress("192.0.2.0", 1) // rfc5737 TEST-NET-1

      Await.result(factory.connect(address), 500.milliseconds) should throwA[SocketTimeoutException]
    }
  }
}
