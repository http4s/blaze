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

package org.http4s.blaze.http.http2

import org.http4s.blaze.testkit.BlazeTestSuite

class StreamIdManagerSuite extends BlazeTestSuite {
  private def newClientManager() = StreamIdManager(true)

  test("A StreamIdManager client mode should start at stream id 1") {
    val manager = newClientManager()
    assertEquals(manager.takeOutboundId(), Some(1))
  }

  test("A StreamIdManager client mode should yield odd number streams") {
    val manager = newClientManager()

    assert((0 until 100).forall { _ =>
      val Some(id) = manager.takeOutboundId()
      id % 2 == 1
    })
  }

  test("A StreamIdManager client mode should not allow an outbound stream id overflow") {
    // Int.MaxValue == 2147483647
    val manager = StreamIdManager.create( /* isClient */ true, Int.MaxValue - 1)

    assert(manager.isIdleOutboundId(Int.MaxValue))

    assertEquals(manager.takeOutboundId(), Some(Int.MaxValue))
    assert(manager.takeOutboundId().isEmpty)

    assertEquals(manager.isIdleOutboundId(1), false)
  }

  test("A StreamIdManager client mode should isOutboundId") {
    val manager = newClientManager()
    assert(manager.isOutboundId(1))
    assertEquals(manager.isOutboundId(2), false)
    assertEquals(manager.isOutboundId(0), false)
  }

  test("A StreamIdManager client mode should isInboundId") {
    val manager = newClientManager()
    assertEquals(manager.isInboundId(1), false)
    assert(manager.isInboundId(2))
    assertEquals(manager.isInboundId(0), false)
  }

  test("A StreamIdManager client mode should isIdleInboundId") {
    val manager = newClientManager()
    assert(manager.observeInboundId(4)) // fast forward

    assertEquals(manager.isIdleInboundId(4), false) // just observed it
    assert(manager.isIdleInboundId(6)) // a real idle stream id

    assertEquals(manager.isIdleInboundId(0), false) // not a valid stream id
    assertEquals(manager.isIdleInboundId(15), false) // inbounds are odd for the client
  }

  test("A StreamIdManager client mode should observeInboundId") {
    val manager = newClientManager()
    assertEquals(manager.observeInboundId(0), false) // not a valid stream id
    assert(manager.observeInboundId(4)) // is idle and not observed

    assertEquals(manager.observeInboundId(4), false) // not idle anymore
    assertEquals(manager.observeInboundId(7), false) // not an inbound id

    assert(manager.observeInboundId(6)) // is idle and not observed
  }

  private def newServerManager() = StreamIdManager(false)

  test("A StreamIdManager server mode start at stream id 2") {
    val manager = newServerManager()
    assertEquals(manager.takeOutboundId(), Some(2))
  }

  test("A StreamIdManager server mode yield even number streams") {
    val manager = newServerManager()
    assert((0 until 100).forall { _ =>
      val Some(id) = manager.takeOutboundId()
      id % 2 == 0
    })
  }

  test("A StreamIdManager server mode not allow a stream id overflow") {
    // Int.MaxValue == 2147483647
    val manager = StreamIdManager.create( /* isClient */ false, Int.MaxValue - 3)

    assert(manager.isIdleOutboundId(Int.MaxValue - 1))

    assertEquals(manager.takeOutboundId(), Some(Int.MaxValue - 1))
    assert(manager.takeOutboundId().isEmpty)

    assertEquals(manager.isIdleOutboundId(2), false)
  }

  test("A StreamIdManager server mode isOutboundId") {
    val manager = newServerManager()
    assertEquals(manager.isOutboundId(1), false)
    assert(manager.isOutboundId(2))
  }

  test("A StreamIdManager server mode isInboundId") {
    val manager = newServerManager()
    assert(manager.isInboundId(1))
    assertEquals(manager.isInboundId(2), false)
  }

  test("A StreamIdManager server mode isIdleInboundId") {
    val manager = newServerManager()
    assert(manager.observeInboundId(5)) // fast forward

    assertEquals(manager.isIdleInboundId(5), false) // just observed it
    assert(manager.isIdleInboundId(7)) // a real idle stream id

    assertEquals(manager.isIdleInboundId(0), false) // not a valid stream id
    assertEquals(manager.isIdleInboundId(16), false) // inbounds are odd for the client
  }
}
