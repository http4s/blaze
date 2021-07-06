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

package org.http4s.blaze.http

import java.nio.ByteBuffer

import org.http4s.blaze.http.BodyReader.BodyReaderOverflowException
import org.http4s.blaze.testkit.BlazeTestSuite
import org.http4s.blaze.util.BufferTools

import scala.concurrent.Future

class BodyReaderSuite extends BlazeTestSuite {
  test("A BodyReader.singleBuffer should return the empty reader if the buffer is empty") {
    val reader = BodyReader.singleBuffer(ByteBuffer.allocate(0))
    assert(reader.isExhausted)
  }

  test("A BodyReader.singleBuffer should provide the buffer on first invocation") {
    val buf = ByteBuffer.allocate(10)
    val reader = BodyReader.singleBuffer(buf)

    assertEquals(reader.isExhausted, false)

    for {
      _ <- assertFuture(reader(), buf)
      _ <- assertFutureBoolean(Future(reader.isExhausted))
    } yield ()
  }

  test("A BodyReader.singleBuffer should  discard clears the buffer") {
    val buf = ByteBuffer.allocate(10)
    val reader = BodyReader.singleBuffer(buf)
    reader.discard()

    assert(reader.isExhausted)
    assertFuture(reader().map(_.hasRemaining), false)
  }

  test("A BodyReader.accumulate(max, bodyReader) should accumulate an empty buffer") {
    val reader = BodyReader.singleBuffer(ByteBuffer.allocate(0))
    val bytes = BodyReader.accumulate(Int.MaxValue, reader).map(_.remaining())
    assertFuture(bytes, 0)
  }

  test("A BodyReader.accumulate(max, bodyReader) should accumulate a single buffer") {
    val reader = BodyReader.singleBuffer(ByteBuffer.allocate(10))
    val bytes = BodyReader.accumulate(Int.MaxValue, reader).map(_.remaining())
    assertFuture(bytes, 10)
  }

  test("A BodyReader.accumulate(max, bodyReader) should accumulate multiple buffers") {
    val reader = new MultiByteReader(
      ByteBuffer.allocate(10),
      ByteBuffer.allocate(1)
    )

    val bytes = BodyReader.accumulate(Int.MaxValue, reader).map(_.remaining())
    assertFuture(bytes, 11)
  }

  test("A BodyReader.accumulate(max, bodyReader) should not overflow on allowed bytes") {
    val ByteCount = 10
    val reader = BodyReader.singleBuffer(ByteBuffer.allocate(ByteCount))
    val bytes = BodyReader.accumulate(ByteCount, reader).map(_.remaining())
    assertFuture(bytes, ByteCount)
  }

  test("A BodyReader.accumulate(max, bodyReader) should not allow overflow") {
    val reader = BodyReader.singleBuffer(ByteBuffer.allocate(10))
    interceptFuture[BodyReaderOverflowException](BodyReader.accumulate(9, reader))
  }

  private class MultiByteReader(data: ByteBuffer*) extends BodyReader {
    private val buffers = new scala.collection.mutable.Queue[ByteBuffer]
    buffers ++= data

    override def discard(): Unit = synchronized(buffers.clear())

    override def apply(): Future[ByteBuffer] =
      synchronized {
        if (!isExhausted) Future.successful(buffers.dequeue())
        else Future.successful(BufferTools.emptyBuffer)
      }

    override def isExhausted: Boolean = synchronized(buffers.isEmpty)
  }
}
