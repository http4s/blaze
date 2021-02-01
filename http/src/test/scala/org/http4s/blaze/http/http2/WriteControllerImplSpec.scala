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
import org.specs2.mutable.Specification

import scala.collection.mutable
import scala.concurrent.{Future, Promise}

class WriteControllerImplSpec extends Specification {
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

  "WriteControllerImpl" should {
    "flush in cycles" in {
      val ctx = new Ctx
      import ctx._

      writeController.write(mockData(0)) must beTrue

      val Write(collection.Seq(d1), p1) = tail.written.dequeue()
      d1 must_== mockData(0)

      // write it again, twice, but it won't flush until p1 is finished
      writeController.write(mockData(1)) must beTrue
      writeController.write(mockData(2)) must beTrue
      tail.written must beEmpty

      p1.trySuccess(())

      tail.written.dequeue().data must_== Seq(mockData(1), mockData(2))
      tail.written.isEmpty must beTrue
    }

    "drain immediately if not flushing" in {
      val ctx = new Ctx
      import ctx._

      writeController.close().isCompleted must beTrue
      writeController.write(mockData(0)) must beFalse
    }

    "wait until empty if close called while flushing" in {
      val ctx = new Ctx
      import ctx._

      val data = mockData(0)
      writeController.write(data) must beTrue

      val f = writeController.close()
      f.isCompleted must beFalse

      // we can write more data still
      writeController.write(data) must beTrue

      tail.written.dequeue().p.trySuccess(())

      // Still false
      f.isCompleted must beFalse

      tail.written.dequeue().p.trySuccess(())
      // Should have drained
      f.isCompleted must beTrue
      writeController.write(data) must beFalse
    }

    "considers write interests during writes" in {
      val ctx = new Ctx
      import ctx._

      val interest = new MockInterest

      writeController.registerWriteInterest(interest) must beTrue
      // register it again, twice, as if it were a different interest
      writeController.registerWriteInterest(interest) must beTrue
      writeController.registerWriteInterest(interest) must beTrue

      interest.calls must_== 1

      val Write(collection.Seq(d1), p1) = tail.written.dequeue()
      d1 must_== mockData(0)

      p1.trySuccess(())
      // Should have gotten to the second and third writes
      interest.calls must_== 3
      tail.written.dequeue().data must_== Seq(mockData(1), mockData(2))
    }

    "write interests hold up draining" in {
      val ctx = new Ctx
      import ctx._

      val interest1 = new MockInterest

      writeController.registerWriteInterest(interest1) must beTrue
      // register it again as if it were a different channel
      writeController.registerWriteInterest(interest1) must beTrue

      interest1.calls must_== 1

      val Write(collection.Seq(data), p) = tail.written.dequeue()
      data must_== mockData(0)

      p.trySuccess(())
      // Should have gotten to the second write
      interest1.calls must_== 2
      tail.written.dequeue().data must_== Seq(mockData(1))
    }
  }
}
