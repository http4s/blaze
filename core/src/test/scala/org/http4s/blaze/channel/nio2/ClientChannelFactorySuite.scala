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

import org.http4s.blaze.testkit.BlazeTestSuite
import org.http4s.blaze.util.TickWheelExecutor

import scala.concurrent.Future
import scala.concurrent.duration.DurationDouble

class ClientChannelFactorySuite extends BlazeTestSuite {
  // The default TickWheelExecutor has 200ms ticks. It should be acceptable for most real world use cases.
  // If one needs very short timeouts (like we do in tests), providing a custom TickWheelExecutor is a solution.
  private val scheduler: TickWheelExecutor = new TickWheelExecutor(tick = 10.millis)

  private val factory =
    new ClientChannelFactory(connectTimeout = 1.millisecond, scheduler = scheduler)
  private val address = new InetSocketAddress("192.0.2.0", 1) // rfc5737 TEST-NET-1

  test("A ClientChannelFactory should time out") {
    for {
      _ <- factory.connect(address).failed.map {
        case _: SocketTimeoutException => ()
        case ex => fail(s"Unexpected exception found $ex")
      }
      _ <- Future(scheduler.shutdown())
    } yield ()
  }
}
