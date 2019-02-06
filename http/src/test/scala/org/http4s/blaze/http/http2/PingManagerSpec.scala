package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.mocks.{MockTools, MockWriteController}
import org.http4s.blaze.util.BufferTools
import org.specs2.mutable.Specification

import scala.util.{Failure, Success}

class PingManagerSpec extends Specification {

  "PingManager" should {
    "write a ping" in {
      val tools = new MockTools(false)

      // send one ping
      val f1 = tools.pingManager.ping()
      val buffer1 = BufferTools.joinBuffers(tools.writeController.observedWrites.toList)
      tools.writeController.observedWrites.clear()

      val ProtocolFrame.Ping(false, pingData1) = ProtocolFrameDecoder.decode(buffer1)
      tools.pingManager.pingAckReceived(pingData1)

      f1.value must beLike {
        case Some(Success(_)) => ok
      }

      // Send another one
      val f2 = tools.pingManager.ping()
      val buffer2 = BufferTools.joinBuffers(tools.writeController.observedWrites.toList)

      val ProtocolFrame.Ping(false, pingData2) = ProtocolFrameDecoder.decode(buffer2)
      tools.pingManager.pingAckReceived(pingData2)

      f2.value must beLike {
        case Some(Success(_)) => ok
      }
    }

    "fail for a ping already in progress" in {
      val tools = new MockTools(false)

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

      val ProtocolFrame.Ping(false, pingData) = ProtocolFrameDecoder.decode(buffer1)
      tools.pingManager.pingAckReceived(pingData)

      f1.value must beLike {
        case Some(Success(_)) => ok
      }
    }

    "fail if the write fails" in {
      val tools = new MockTools(true) {
        override val writeController: MockWriteController = new MockWriteController {
          override def write(data: Seq[ByteBuffer]): Boolean = false
        }
      }

      // ping should fail
      tools.pingManager.ping().value must beLike {
        case Some(Failure(_)) => ok
      }
    }

    "closing fails pending ping" in {
      val tools = new MockTools(true)

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
