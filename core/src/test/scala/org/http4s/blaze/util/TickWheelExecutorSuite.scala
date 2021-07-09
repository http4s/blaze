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

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import munit.FunSuite

import scala.util.Try

class TickWheelExecutorSuite extends FunSuite {
  import scala.concurrent.duration._

  private val ec = new TickWheelExecutor(tick = 100.millis)

  test("A TickWheelExecutor should execute a simple task with no delay") {
    val i = new AtomicInteger(0)
    ec.schedule(() => i.set(1), Duration.Zero)

    assertEquals(i.get(), 1)
  }

  test("A TickWheelExecutor should execute a simple task with negative delay") {
    val i = new AtomicInteger(0)
    ec.schedule(() => i.set(1), -2.seconds)

    assertEquals(i.get(), 1)
  }

  test("A TickWheelExecutor should not schedule a simple task with Inf delay") {
    val i = new AtomicInteger(0)
    ec.schedule(() => i.set(1), Duration.Inf)
    Thread.sleep(100)
    assertEquals(i.get(), 0)
  }

  test("A TickWheelExecutor should execute a simple task with a short delay") {
    val l = new CountDownLatch(1)
    ec.schedule(() => l.countDown(), 200.millis)

    assert(l.await(10, TimeUnit.SECONDS))
  }

  test("A TickWheelExecutor should execute a simple task with a multi clock revolution delay") {
    val ec = new TickWheelExecutor(3, 2.seconds)
    val latch = new CountDownLatch(1)
    ec.schedule(() => latch.countDown(), 7.seconds)

    Thread.sleep(4000) // Shouldn't be done yet
    assertEquals(latch.getCount, 1L)

    assert(latch.await(10, TimeUnit.SECONDS))
  }

  test("A TickWheelExecutor should execute many delayed tasks") {
    val ec = new TickWheelExecutor(3, 3.millis)
    val latch = new CountDownLatch(1000)

    (0 until 1000).foreach { j =>
      ec.schedule(() => latch.countDown(), j.millis)
    }

    assert(latch.await(10, TimeUnit.SECONDS))
  }

  test("A TickWheelExecutor should prune many tasks") {
    val ec = new TickWheelExecutor(3, 10.millis)
    val latch = new CountDownLatch(500)

    val cancels = (0 until 1000).map { j =>
      val c = ec.schedule(() => latch.countDown(), (j + 500).millis)
      c
    }
    cancels.zipWithIndex.foreach { case (r, i) => if (i % 2 == 0) r.cancel() }

    assert(latch.await(10, TimeUnit.SECONDS))
  }

  test("A TickWheelExecutor should gracefully handle exceptions") {
    val latch = new CountDownLatch(2)
    val ec = new TickWheelExecutor(3, 1.millis) {
      override protected def onNonFatal(t: Throwable): Unit = {
        latch.countDown()
        super.onNonFatal(t)
      }
    }

    ec.schedule(() => sys.error("Woops!"), 3.millis)

    ec.schedule(() => sys.error("Woops!"), Duration.Zero)

    assert(latch.await(5, TimeUnit.SECONDS))
  }

  test("A TickWheelExecutor should shutdown") {
    val ec = new TickWheelExecutor(3, 10.millis)
    ec.shutdown()

    val result1 = Try(ec.schedule(() => sys.error("Woops!"), Duration.Zero))

    result1.fold(
      {
        case _: RuntimeException => ()
        case ex => fail(s"Unexpected exception found $ex")
      },
      _ => fail("A TickWheelExecutor should shutdown")
    )

    val result2 = Try(ec.schedule(() => sys.error("Woops!"), Duration.Inf))

    result2.fold(
      {
        case _: RuntimeException => ()
        case ex => fail(s"Unexpected exception found $ex")
      },
      _ => fail("A TickWheelExecutor should shutdown")
    )
  }
}
