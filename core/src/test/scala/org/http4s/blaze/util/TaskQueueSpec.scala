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

import org.specs2.mutable.Specification

import scala.util.control.ControlThrowable

class TaskQueueSpec extends Specification {
  "TaskQueue" >> {
    "offer a task" >> {
      val q = new TaskQueue
      q.needsExecution must beFalse
      q.isClosed must beFalse

      q.enqueueTask(new Runnable { def run(): Unit = () }) must_== TaskQueue.FirstEnqueued
      q.enqueueTask(new Runnable { def run(): Unit = () }) must_== TaskQueue.Enqueued
      q.needsExecution must beTrue
    }

    "reject a tasks when closed" >> {
      val q = new TaskQueue
      q.needsExecution must beFalse
      q.isClosed must beFalse

      // the closing latch will be await on by the main thread before testing the behavior
      val closingLatch = new CountDownLatch(1)
      q.enqueueTask(
        new Runnable { def run(): Unit = closingLatch.countDown() }) must_== TaskQueue.FirstEnqueued

      // this latch makes the closing thread block *in* the close call, giving us a chance to test
      val blockCloseLatch = new CountDownLatch(1)
      q.enqueueTask(
        new Runnable { def run(): Unit = blockCloseLatch.await() }) must_== TaskQueue.Enqueued

      // The closing thread will close shop, first ticking the `closingLatch`, then waiting for this thread
      val closingThead = new Thread(new Runnable { def run(): Unit = q.close() })
      closingThead.start()

      // once this method call returns we know the closing thread is in the `close()` call
      closingLatch.await(30, TimeUnit.SECONDS) must beTrue
      q.isClosed must beTrue
      closingThead.isAlive must beTrue

      q.enqueueTask(new Runnable { def run(): Unit = () }) must_== TaskQueue.Closed
      blockCloseLatch.countDown()
      closingThead.join(30000) // wait at most 30 seconds
      closingThead.isAlive must beFalse

      q.isClosed must beTrue
      q.enqueueTask(new Runnable { def run(): Unit = () }) must_== TaskQueue.Closed
    }

    "execute tasks added by this thread while executing" >> {
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
      count.get must_== 10
    }

    "execute tasks added by another thread while executing" >> {
      val q = new TaskQueue

      // let the main thread know we're mid-execution
      val executingLatch = new CountDownLatch(1)
      q.enqueueTask(new Runnable { def run(): Unit = executingLatch.countDown() })

      // Wait for the main thread to unblock
      val sleepLatch = new CountDownLatch(1)
      q.enqueueTask(new Runnable { def run(): Unit = sleepLatch.await() })

      val t = new Thread(new Runnable { def run(): Unit = q.executeTasks() })
      t.start()

      // wait for the worker thread to enter the work loop
      executingLatch.await(30, TimeUnit.SECONDS) must beTrue

      // Add a task from this thread
      val counter = new AtomicInteger(0)
      q.enqueueTask(new Runnable {
        def run(): Unit = {
          counter.incrementAndGet()
          ()
        }
      }) must_== TaskQueue.Enqueued

      sleepLatch.countDown()
      t.join(30000) // 30 seconds max
      counter.get must_== 1
    }

    "`NonFatal` and `ControlThrowable`s are caught by the execute loop" >> {
      val q = new TaskQueue
      q.enqueueTask(new Runnable {
        def run(): Unit = throw new Exception("sadface")
      })

      q.enqueueTask(new Runnable {
        def run(): Unit = throw new ControlThrowable {}
      })

      q.executeTasks()
      q.needsExecution must beFalse
    }
  }
}
