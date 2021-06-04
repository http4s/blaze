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
import java.nio.ByteBuffer

import cats.effect.IO
import cats.effect.kernel.Resource
import munit.{CatsEffectSuite, TestOptions}
import org.http4s.blaze.pipeline.HeadStage
import org.http4s.blaze.util.TickWheelExecutor

import scala.concurrent.duration.DurationDouble

class ClientChannelFactorySuite extends CatsEffectSuite {
  // The default TickWheelExecutor has 200ms ticks. It should be acceptable for most real world use cases.
  // If one needs very short timeouts (like we do in tests), providing a custom TickWheelExecutor is a solution.
  private val scheduler: TickWheelExecutor = new TickWheelExecutor(tick = 10.millis)

  private val factory =
    new ClientChannelFactory(connectTimeout = 1.millisecond, scheduler = scheduler)
  private val address = new InetSocketAddress("192.0.2.0", 1) // rfc5737 TEST-NET-1

  private type Result = Either[Throwable, HeadStage[ByteBuffer]]

  private val resource = Resource.eval(IO.fromFuture(IO(factory.connect(address))).attempt)
  private val setup = (_: TestOptions, _: Result) => IO.unit
  private val tearDown = (_: Result) => IO(scheduler.shutdown())

  ResourceFixture(resource, setup, tearDown).test("A ClientChannelFactory should time out") {
    case Left(err) =>
      err match {
        case _: SocketTimeoutException => ()
        case ex => fail(s"Unexpected exception found $ex")
      }

    case Right(_) => fail("A ClientChannelFactory should time out")
  }
}
