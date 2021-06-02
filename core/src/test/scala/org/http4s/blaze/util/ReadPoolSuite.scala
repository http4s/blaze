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
import munit.FunSuite

import scala.collection.mutable.ListBuffer
import scala.concurrent.{Await, Awaitable}
import scala.concurrent.duration._
import scala.util.Try

class ReadPoolSuite extends FunSuite {
  private def await[T](t: Awaitable[T]): T = Await.result(t, 1.second)

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

    assertEquals(await(p.read()), 1)
    assertEquals(p.observed, List(1))
  }

  test("A ReadPool should enqueue multiple messages") {
    val p = new TrackingReadPool
    (0 until 10).foreach { i =>
      assert(p.offer(i))
      assertEquals(p.observed.length, 0)
    }

    (0 until 10).foreach { i =>
      assertEquals(await(p.read()), i)
      assertEquals(p.observed, (0 to i).toList)
    }
  }

  test("A ReadPool should enqueue a single read") {
    val p = new TrackingReadPool
    val f = p.read()

    assert(f.value.isEmpty)
    assert(p.offer(1))
    assertEquals(await(f), 1)
  }

  test("A ReadPool should fail to enqueue two reads") {
    val p = new TrackingReadPool
    p.read()

    val result = Try(await(p.read()))

    result.fold(
      {
        case _: IllegalStateException => ()
        case ex => fail(s"Unexpected exception found $ex")
      },
      _ => fail("A ReadPool should fail to enqueue two reads")
    )
  }

  test("A ReadPool should close fails pending reads") {
    val p = new TrackingReadPool
    val f = p.read()

    p.close()

    Try(await(f)).fold(
      {
        case _: Command.EOF.type => ()
        case ex => fail(s"Unexpected exception found $ex")
      },
      _ => fail("A ReadPool should close fails pending reads")
    )

    val subsequentF = Try(await(p.read()))

    // subsequent reads fail too
    subsequentF.fold(
      {
        case _: Command.EOF.type => ()
        case ex => fail(s"Unexpected exception found ${ex.getMessage}")
      },
      _ => fail("A ReadPool should close fails pending reads")
    )

    // Offers must return false after closed
    assertEquals(p.offer(1), false)
  }
}
