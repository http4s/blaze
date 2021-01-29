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

import org.specs2.mutable.Specification

class StreamIdManagerSpec extends Specification {
  "StreamIdManager" in {
    "client mode" should {
      def newManager() = StreamIdManager(true)

      "Start at stream id 1" in {
        val manager = newManager()
        manager.takeOutboundId() must_== Some(1)
      }

      "Yield odd number streams" in {
        val manager = newManager()
        forall(0 until 100) { _ =>
          val Some(id) = manager.takeOutboundId()
          id % 2 must_== 1
        }
      }

      "Not allow an outbound stream id overflow" in {
        // Int.MaxValue == 2147483647
        val manager = StreamIdManager.create( /* isClient */ true, Int.MaxValue - 1)

        manager.isIdleOutboundId(Int.MaxValue) must beTrue

        manager.takeOutboundId() must_== Some(Int.MaxValue)
        manager.takeOutboundId() must_== None

        manager.isIdleOutboundId(1) must beFalse
      }

      "isOutboundId" in {
        val manager = newManager()
        manager.isOutboundId(1) must beTrue
        manager.isOutboundId(2) must beFalse
        manager.isOutboundId(0) must beFalse
      }

      "isInboundId" in {
        val manager = newManager()
        manager.isInboundId(1) must beFalse
        manager.isInboundId(2) must beTrue
        manager.isInboundId(0) must beFalse
      }

      "isIdleInboundId" in {
        val manager = newManager()
        manager.observeInboundId(4) must beTrue // fast forward

        manager.isIdleInboundId(4) must beFalse // just observed it
        manager.isIdleInboundId(6) must beTrue // a real idle stream id

        manager.isIdleInboundId(0) must beFalse // not a valid stream id
        manager.isIdleInboundId(15) must beFalse // inbounds are odd for the client
      }

      "observeInboundId" in {
        val manager = newManager()
        manager.observeInboundId(0) must beFalse // not a valid stream id
        manager.observeInboundId(4) must beTrue // is idle and not observed

        manager.observeInboundId(4) must beFalse // not idle anymore
        manager.observeInboundId(7) must beFalse // not an inbound id

        manager.observeInboundId(6) must beTrue // is idle and not observed
      }
    }

    "server mode" should {
      def newManager() = StreamIdManager(false)

      "Start at stream id 2" in {
        val manager = newManager()
        manager.takeOutboundId() must_== Some(2)
      }

      "Yield even number streams" in {
        val manager = newManager()
        forall(0 until 100) { _ =>
          val Some(id) = manager.takeOutboundId()
          id % 2 must_== 0
        }
      }

      "Not allow a stream id overflow" in {
        // Int.MaxValue == 2147483647
        val manager = StreamIdManager.create( /* isClient */ false, Int.MaxValue - 3)

        manager.isIdleOutboundId(Int.MaxValue - 1) must beTrue

        manager.takeOutboundId() must_== Some(Int.MaxValue - 1)
        manager.takeOutboundId() must_== None

        manager.isIdleOutboundId(2) must beFalse
      }

      "isOutboundId" in {
        val manager = newManager()
        manager.isOutboundId(1) must beFalse
        manager.isOutboundId(2) must beTrue
      }

      "isInboundId" in {
        val manager = newManager()
        manager.isInboundId(1) must beTrue
        manager.isInboundId(2) must beFalse
      }

      "isIdleInboundId" in {
        val manager = newManager()
        manager.observeInboundId(5) must beTrue // fast forward

        manager.isIdleInboundId(5) must beFalse // just observed it
        manager.isIdleInboundId(7) must beTrue // a real idle stream id

        manager.isIdleInboundId(0) must beFalse // not a valid stream id
        manager.isIdleInboundId(16) must beFalse // inbounds are odd for the client
      }
    }
  }
}
