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

import cats.effect.IO
import munit.CatsEffectSuite
import org.http4s.blaze.pipeline.Command

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.duration._

class ReadPoolSuite extends CatsEffectSuite {
  private def toIO[A](computation: => Future[A]) =
    IO.fromFuture(IO(computation).timeout(1.second))

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

    assertIO(toIO(p.read()), 1) *>
      assertIO(IO(p.observed), List(1))
  }

  test("A ReadPool should enqueue multiple messages") {
    val p = new TrackingReadPool
    (0 until 10).foreach { i =>
      assert(p.offer(i))
      assertEquals(p.observed.length, 0)
    }

    (0 until 10).foreach { i =>
      assertIO(toIO(p.read()), i) *>
        assertIO(IO(p.observed), (0 to i).toList)
    }
  }

  test("A ReadPool should enqueue a single read") {
    val p = new TrackingReadPool
    val f = p.read()

    assert(f.value.isEmpty)
    assert(p.offer(1))
    assertIO(toIO(f), 1)
  }

  test("A ReadPool should fail to enqueue two reads") {
    val p = new TrackingReadPool
    p.read()

    val result = toIO(p.read()).attempt.map {
      case Left(err) =>
        err match {
          case _: IllegalStateException => true
          case _ => false
        }

      case Right(_) => false
    }

    assertIOBoolean(result)
  }

  test("A ReadPool should close fails pending reads") {
    val p = new TrackingReadPool
    val f = p.read()

    p.close()

    val result1 =
      toIO(f).attempt.map {
        case Left(Command.EOF) => true
        case _ => false
      }

    // subsequent reads should fail too
    val subsequentF =
      toIO(p.read()).attempt.map {
        case Left(Command.EOF) => true
        case _ => false
      }

    assertIOBoolean(result1) *>
      assertIOBoolean(subsequentF) *>
      assertIO(IO(p.offer(1)), false)
  }
}
