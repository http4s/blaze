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

import munit.FunSuite

class ExecutionSuite extends FunSuite {
  private def trampoline = Execution.trampoline

  private def toRunnable(f: => Unit): Runnable = () => f

  private def submit(f: => Unit): Unit = trampoline.execute(toRunnable(f))

  test("A thread local executor should submit a working job") {
    var i = 0

    submit {
      i += 1
    }

    assertEquals(i, 1)
  }

  test("A thread local executor should submit multiple working jobs") {
    var i = 0

    for (_ <- 0 until 10)
      submit {
        i += 1
      }

    assertEquals(i, 10)
  }

  test("A thread local executor should submit jobs from within a job") {
    var i = 0

    submit {
      for (_ <- 0 until 10)
        submit {
          i += 1
        }
    }

    assertEquals(i, 10)
  }

  test("A thread local executor should submit a failing job") {
    var i = 0

    submit {
      sys.error("Boom")
      i += 1
    }

    assertEquals(i, 0)
  }

  test("A thread local executor should interleave failing and successful `Runnables`") {
    var i = 0

    submit {
      for (j <- 0 until 10)
        submit {
          if (j % 2 == 0) submit(i += 1)
          else submit(sys.error("Boom"))
        }
    }

    assertEquals(i, 5)
  }

  test("A thread local executor should not blow the stack") {
    val iterations = 500000
    var i = 0

    def go(j: Int): Unit =
      submit {
        if (j < iterations) {
          i += 1
          go(j + 1)
        }
      }

    go(0)

    assertEquals(i, iterations)
  }
}
