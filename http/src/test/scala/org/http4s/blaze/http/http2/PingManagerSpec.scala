package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.mocks.{MockHeaderAggregatingFrameListener, MockTools, MockWriteController}
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

import scala.util.{Failure, Success}

class PingManagerSpec extends Specification {

  private class Ctx {
    var pingData: Array[Byte] = Array.empty
    val tools: MockTools = new MockTools(false) {
      override lazy val frameListener: MockHeaderAggregatingFrameListener = new MockHeaderAggregatingFrameListener {
        override def onPingFrame(ack: Boolean, data: Array[Byte]): Result = {
          assert(!ack)
          pingData = data
          Continue
        }
      }
    }
  }

  "PingManager" should {
    "write a ping" in {
      val ctx = new Ctx
      import ctx._

      // send one ping
      val f1 = tools.pingManager.ping()
      val buffer1 = BufferTools.joinBuffers(tools.writeController.observedWrites.toList)
      tools.writeController.observedWrites.clear()

      tools.http2Decoder.decodeBuffer(buffer1) must_== Continue
      tools.pingManager.pingAckReceived(pingData)

      f1.value must beLike {
        case Some(Success(_)) => ok
      }

      // Send another one
      val f2 = tools.pingManager.ping()
      val buffer2 = BufferTools.joinBuffers(tools.writeController.observedWrites.toList)

      tools.http2Decoder.decodeBuffer(buffer2) must_== Continue
      tools.pingManager.pingAckReceived(pingData)

      f2.value must beLike {
        case Some(Success(_)) => ok
      }
    }

    "fail for a ping already in progress" in {
      val ctx = new Ctx
      import ctx._

      // send one ping
      val f1 = tools.pingManager.ping()

      // Send another one that will fail
      val f2 = tools.pingManager.ping()

      f2.value must beLike {
        case Some(Failure(_)) => ok
      }

      // make the first one complete
      val buffer1 = BufferTools.joinBuffers(tools.writeController.observedWrites.toList)
      tools.writeController.observedWrites.clear()

      tools.http2Decoder.decodeBuffer(buffer1) must_== Continue
      tools.pingManager.pingAckReceived(pingData)

      f1.value must beLike {
        case Some(Success(_)) => ok
      }
    }

    "fail if the write fails" in {
      val ctx = new Ctx {
        override val tools: MockTools = new MockTools(true) {
          override val writeController: MockWriteController = new MockWriteController {
            override def write(data: Seq[ByteBuffer]): Boolean = false
          }
        }
      }
      import ctx._

      // ping should fail
      tools.pingManager.ping().value must beLike {
        case Some(Failure(_)) => ok
      }
    }

    "closing fails pending ping" in {
      val ctx = new Ctx
      import ctx._

      // ping should fail
      val f = tools.pingManager.ping()

      f.isCompleted must beFalse
      tools.pingManager.close()

      tools.pingManager.ping().value must beLike {
        case Some(Failure(_)) => ok
      }
    }
  }
}
