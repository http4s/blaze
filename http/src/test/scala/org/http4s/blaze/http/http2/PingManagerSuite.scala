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

package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.mocks.{MockTools, MockWriteController}
import org.http4s.blaze.testkit.BlazeTestSuite
import org.http4s.blaze.util.BufferTools

import scala.util.{Failure, Success}

class PingManagerSuite extends BlazeTestSuite {
  test("A PingManager should write a ping") {
    val tools = new MockTools(false)

    // send one ping
    val f1 = tools.pingManager.ping()
    val buffer1 = BufferTools.joinBuffers(tools.writeController.observedWrites.toList)
    tools.writeController.observedWrites.clear()

    val ProtocolFrame.Ping(false, pingData1) = ProtocolFrameDecoder.decode(buffer1)
    tools.pingManager.pingAckReceived(pingData1)

    f1.value match {
      case Some(Success(_)) => ()
      case _ => fail("Unexpected ping result found")
    }

    // Send another one
    val f2 = tools.pingManager.ping()
    val buffer2 = BufferTools.joinBuffers(tools.writeController.observedWrites.toList)

    val ProtocolFrame.Ping(false, pingData2) = ProtocolFrameDecoder.decode(buffer2)
    tools.pingManager.pingAckReceived(pingData2)

    f2.value match {
      case Some(Success(_)) => ()
      case _ => fail("Unexpected ping result found")
    }
  }

  test("A PingManager should fail for a ping already in progress") {
    val tools = new MockTools(false)

    // send one ping
    val f1 = tools.pingManager.ping()

    // Send another one that will fail
    val f2 = tools.pingManager.ping()

    f2.value match {
      case Some(Failure(_)) => ()
      case _ => fail("Unexpected ping result found")
    }

    // make the first one complete
    val buffer1 = BufferTools.joinBuffers(tools.writeController.observedWrites.toList)
    tools.writeController.observedWrites.clear()

    val ProtocolFrame.Ping(false, pingData) = ProtocolFrameDecoder.decode(buffer1)
    tools.pingManager.pingAckReceived(pingData)

    f1.value match {
      case Some(Success(_)) => ()
      case _ => fail("Unexpected ping result found")
    }
  }

  test("A PingManager should fail if the write fails") {
    val tools = new MockTools(true) {
      override val writeController: MockWriteController = new MockWriteController {
        override def write(data: Seq[ByteBuffer]): Boolean = false
      }
    }

    // ping should fail
    tools.pingManager.ping().value match {
      case Some(Failure(_)) => ()
      case _ => fail("Unexpected ping result found")
    }
  }

  test("A PingManager should closing fails pending ping") {
    val tools = new MockTools(true)

    // ping should fail
    val f = tools.pingManager.ping()

    assertEquals(f.isCompleted, false)
    tools.pingManager.close()

    tools.pingManager.ping().value match {
      case Some(Failure(_)) => ()
      case _ => fail("Unexpected ping result found")
    }
  }
}
