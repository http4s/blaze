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

import org.http4s.blaze.testkit.BlazeTestSuite

class ByteBufferInputStreamSuite extends BlazeTestSuite {
  private def fromByteBuffer(buffer: ByteBuffer): ByteBufferInputStream =
    new ByteBufferInputStream(buffer)

  private def fromBytes(bytes: Byte*): ByteBufferInputStream =
    fromByteBuffer(ByteBuffer.wrap(bytes.toArray))

  test("A ByteBufferInputStream should report available bytes") {
    assert((0 until 10).forall { i =>
      val bb = ByteBuffer.wrap(new Array[Byte](i))
      fromByteBuffer(bb).available() == i
    })
  }

  test("A ByteBufferInputStream should read -1 when bytes unavailable") {
    assertEquals(fromBytes().read(), -1)
  }

  test("A ByteBufferInputStream should read byte when available") {
    val range = 0 until 10
    val is = fromBytes(range.map(_.toByte): _*)
    assert(range.forall(i => is.read() == i))

    assertEquals(is.available(), 0)
    assertEquals(is.read(), -1)
  }

  test("A ByteBufferInputStream should handle mark and reset apporpriately") {
    val is = fromBytes((0 until 10).map(_.toByte): _*)

    assert(is.markSupported())

    assertEquals(is.read(), 0)
    is.mark(1)
    assertEquals(is.available(), 9)
    assertEquals(is.read(), 1)
    assertEquals(is.available(), 8)
    is.reset()
    assertEquals(is.available(), 9)
    assertEquals(is.read(), 1)
    assertEquals(is.available(), 8)
  }
}
