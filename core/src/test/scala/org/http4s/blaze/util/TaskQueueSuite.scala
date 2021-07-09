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

import scala.util.control.ControlThrowable

class TaskQueueSuite extends FunSuite {
  test("A TaskQueue should offer a task") {
    val q = new TaskQueue
    assertEquals(q.needsExecution, false)
    assertEquals(q.isClosed, false)

    assertEquals(q.enqueueTask(() => ()), TaskQueue.FirstEnqueued)
    assertEquals(q.enqueueTask(() => ()), TaskQueue.Enqueued)
    assert(q.needsExecution)
  }

  test("A TaskQueue should reject a tasks when closed") {
    val q = new TaskQueue
    assertEquals(q.needsExecution, false)
    assertEquals(q.isClosed, false)

    // the closing latch will be await on by the main thread before testing the behavior
    val closingLatch = new CountDownLatch(1)
    assertEquals(q.enqueueTask(() => closingLatch.countDown()), TaskQueue.FirstEnqueued)

    // this latch makes the closing thread block *in* the close call, giving us a chance to test
    val blockCloseLatch = new CountDownLatch(1)
    assertEquals(q.enqueueTask(() => blockCloseLatch.await()), TaskQueue.Enqueued)

    // The closing thread will close shop, first ticking the `closingLatch`, then waiting for this thread
    val closingThead = new Thread(() => q.close())
    closingThead.start()

    // once this method call returns we know the closing thread is in the `close()` call
    assert(closingLatch.await(30, TimeUnit.SECONDS))
    assert(q.isClosed)
    assert(closingThead.isAlive)

    assertEquals(q.enqueueTask(() => ()), TaskQueue.Closed)
    blockCloseLatch.countDown()
    closingThead.join(30000) // wait at most 30 seconds
    assertEquals(closingThead.isAlive, false)

    assert(q.isClosed)
    assertEquals(q.enqueueTask(() => ()), TaskQueue.Closed)
  }

  test("A TaskQueue should execute tasks added by this thread while executing") {
    val q = new TaskQueue
    val count = new AtomicInteger(0)

    class SelfEqueuer(remaining: Int) extends Runnable {
      override def run(): Unit =
        if (0 < remaining) {
          count.incrementAndGet()
          q.enqueueTask(new SelfEqueuer(remaining - 1))
          ()
        }
    }

    q.enqueueTask(new SelfEqueuer(10))
    q.executeTasks()
    assertEquals(count.get, 10)
  }

  test("A TaskQueue should execute tasks added by another thread while executing") {
    val q = new TaskQueue

    // let the main thread know we're mid-execution
    val executingLatch = new CountDownLatch(1)
    q.enqueueTask(() => executingLatch.countDown())

    // Wait for the main thread to unblock
    val sleepLatch = new CountDownLatch(1)
    q.enqueueTask(() => sleepLatch.await())

    val t = new Thread(() => q.executeTasks())
    t.start()

    // wait for the worker thread to enter the work loop
    assert(executingLatch.await(30, TimeUnit.SECONDS))

    // Add a task from this thread
    val counter = new AtomicInteger(0)
    assertEquals(
      q.enqueueTask { () =>
        counter.incrementAndGet()
        ()
      },
      TaskQueue.Enqueued)

    sleepLatch.countDown()
    t.join(30000) // 30 seconds max
    assertEquals(counter.get, 1)
  }

  test("A TaskQueue should catch `NonFatal` and `ControlThrowable`s by the execute loop") {
    val q = new TaskQueue
    q.enqueueTask(() => throw new Exception("sadface"))

    q.enqueueTask(() => throw new ControlThrowable {})

    q.executeTasks()
    assertEquals(q.needsExecution, false)
  }
}
