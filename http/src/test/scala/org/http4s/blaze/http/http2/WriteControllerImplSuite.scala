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

import org.http4s.blaze.http.http2.mocks.MockTools
import org.http4s.blaze.pipeline.TailStage
import org.http4s.blaze.testkit.BlazeTestSuite

import scala.collection.mutable
import scala.concurrent.{Future, Promise}

class WriteControllerImplSuite extends BlazeTestSuite {
  private def mockData(i: Int): ByteBuffer =
    ByteBuffer.wrap {
      (0 to i).map(_.toByte).toArray
    }

  private class MockInterest extends WriteInterest {
    var calls = 0

    def performStreamWrite(): Seq[ByteBuffer] = {
      calls += 1
      mockData(calls - 1) :: Nil
    }
  }

  private case class Write(data: collection.Seq[ByteBuffer], p: Promise[Unit])

  private class MockTail extends TailStage[ByteBuffer] {
    override def name: String = "MockTail"

    val written = mutable.Queue.empty[Write]

    override def channelWrite(data: ByteBuffer): Future[Unit] =
      channelWrite(data :: Nil)

    override def channelWrite(data: collection.Seq[ByteBuffer]): Future[Unit] = {
      val p = Promise[Unit]()
      written += Write(data, p)
      p.future
    }
  }

  private class Ctx {
    def highWaterMark = Int.MaxValue
    val tools = new MockTools(true)
    val tail = new MockTail
    val writeController = new WriteControllerImpl(tools, highWaterMark, tail)
  }

  test("A WriteControllerImpl should flush in cycles") {
    val ctx = new Ctx
    import ctx._

    assert(writeController.write(mockData(0)))

    val Write(collection.Seq(d1), p1) = tail.written.dequeue()
    assertEquals(d1, mockData(0))

    // write it again, twice, but it won't flush until p1 is finished
    assert(writeController.write(mockData(1)))
    assert(writeController.write(mockData(2)))
    assert(tail.written.isEmpty)

    p1.trySuccess(())

    assertEquals(tail.written.dequeue().data, Seq(mockData(1), mockData(2)))
    assert(tail.written.isEmpty)
  }

  test("A WriteControllerImpl should drain immediately if not flushing") {
    val ctx = new Ctx
    import ctx._

    assert(writeController.close().isCompleted)
    assertEquals(writeController.write(mockData(0)), false)
  }

  test("A WriteControllerImpl should wait until empty if close called while flushing") {
    val ctx = new Ctx
    import ctx._

    val data = mockData(0)
    assert(writeController.write(data))

    val f = writeController.close()
    assertEquals(f.isCompleted, false)

    // we can write more data still
    assert(writeController.write(data))

    tail.written.dequeue().p.trySuccess(())

    // Still false
    assertEquals(f.isCompleted, false)

    tail.written.dequeue().p.trySuccess(())
    // Should have drained
    assert(f.isCompleted)
    assertEquals(writeController.write(data), false)
  }

  test("A WriteControllerImpl should considers write interests during writes") {
    val ctx = new Ctx
    import ctx._

    val interest = new MockInterest

    assert(writeController.registerWriteInterest(interest))
    // register it again, twice, as if it were a different interest
    assert(writeController.registerWriteInterest(interest))
    assert(writeController.registerWriteInterest(interest))

    assertEquals(interest.calls, 1)

    val Write(collection.Seq(d1), p1) = tail.written.dequeue()
    assertEquals(d1, mockData(0))

    p1.trySuccess(())
    // Should have gotten to the second and third writes
    assertEquals(interest.calls, 3)
    assertEquals(tail.written.dequeue().data, Seq(mockData(1), mockData(2)))
  }

  test("A WriteControllerImpl should write interests hold up draining") {
    val ctx = new Ctx
    import ctx._

    val interest1 = new MockInterest

    assert(writeController.registerWriteInterest(interest1))
    // register it again as if it were a different channel
    assert(writeController.registerWriteInterest(interest1))

    assertEquals(interest1.calls, 1)

    val Write(collection.Seq(data), p) = tail.written.dequeue()
    assertEquals(data, mockData(0))

    p.trySuccess(())
    // Should have gotten to the second write
    assertEquals(interest1.calls, 2)
    assertEquals(tail.written.dequeue().data, Seq(mockData(1)))
  }
}
