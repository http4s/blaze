package org.http4s.blaze.util

import org.specs2.mutable.Specification
import Execution.{directec, trampoline}

class ExecutionSpec extends Specification {
  def toRunnable(f: => Unit): Runnable = new Runnable {
    override def run(): Unit = f
  }

  "trampoline" should {
    def submit(f: => Unit): Unit = trampoline.execute(toRunnable(f))

    "submit a working job" in {
      var i = 0

      submit {
        i += 1
      }

      i must be equalTo(1)
    }

    "submit multiple working jobs" in {
      var i = 0

      for (_ <- 0 until 10) {
        submit {
          i += 1
        }
      }

      i must be equalTo(10)
    }

    "submit jobs from within a job" in {
      var i = 0

      submit {
        for (_ <- 0 until 10) {
          submit {
            i += 1
          }
        }
      }

      i must be equalTo(10)
    }

    "submit a failing job" in {
      var i = 0

      submit {
        sys.error("Boom")
        i += 1
      }

      i must be equalTo(0)
    }

    "interleave failing and successful `Runnables`" in {
      var i = 0

      submit {
        for (j <- 0 until 10) {
          submit {
            if (j % 2 == 0) submit { i += 1 }
            else submit { sys.error("Boom") }
          }
        }
      }

      i must  be equalTo(5)
    }

    "Not blow the stack" in {
      val iterations = 500000
      var i = 0

      def go(j: Int): Unit = submit {
        if (j < iterations) {
          i += 1
          go(j+1)
        }
      }

      go(0)

      i must be equalTo(iterations)
    }

    "not catch fatal exceptions" in {
      submit {
        throw new VirtualMachineError("FATAL!") {}
      } must throwAn[Error]
    }
  }

  "directec" should {
    def submit(f: => Unit): Unit = directec.execute(toRunnable(f))

    "not catch fatal exceptions" in {
      submit {
        throw new VirtualMachineError("FATAL!") {}
      } must throwAn[VirtualMachineError]
    }
  }
}
