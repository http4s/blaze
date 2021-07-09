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

package org.http4s.blaze.util

import org.http4s.blaze.pipeline.Command
import org.http4s.blaze.testkit.BlazeTestSuite

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

class ReadPoolSuite extends BlazeTestSuite {
  private class TrackingReadPool extends ReadPool[Int] {
    private[this] val obs = new ListBuffer[Int]

    def observed: List[Int] = obs.result()

    override def messageConsumed(t: Int): Unit = {
      obs += t
      ()
    }
  }

  test("A ReadPool should enqueue an offer") {
    val p = new TrackingReadPool
    assert(p.offer(1))
    assertEquals(p.observed.length, 0)

    for {
      _ <- assertFuture(p.read(), 1)
      _ <- assertFuture(Future.successful(p.observed), List(1))
    } yield ()
  }

  test("A ReadPool should enqueue multiple messages") {
    val p = new TrackingReadPool
    (0 until 10).foreach { i =>
      assert(p.offer(i))
      assertEquals(p.observed.length, 0)
    }

    (0 until 10).foreach { i =>
      for {
        _ <- assertFuture(p.read(), 1)
        _ <- assertFuture(Future.successful(p.observed), (0 to i).toList)
      } yield ()
    }
  }

  test("A ReadPool should enqueue a single read") {
    val p = new TrackingReadPool
    val f = p.read()

    assert(f.value.isEmpty)
    assert(p.offer(1))
    assertFuture(f, 1)
  }

  test("A ReadPool should fail to enqueue two reads") {
    val p = new TrackingReadPool
    p.read()

    val result = p.read().failed.map {
      case _: IllegalStateException => true
      case _ => false
    }

    assertFutureBoolean(result)
  }

  test("A ReadPool should close fails pending reads") {
    val p = new TrackingReadPool
    val f = p.read()

    p.close()

    def result1 =
      f.failed.map {
        case Command.EOF => true
        case _ => false
      }

    // subsequent reads should fail too
    def subsequentF =
      p.read().failed.map {
        case Command.EOF => true
        case _ => false
      }

    for {
      _ <- assertFutureBoolean(result1)
      _ <- assertFutureBoolean(subsequentF)
      _ <- assertFuture(Future(p.offer(1)), false)
    } yield ()
  }
}
