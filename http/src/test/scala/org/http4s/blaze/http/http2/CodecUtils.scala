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
import java.nio.charset.StandardCharsets

import org.http4s.blaze.util.BufferTools._

private[http2] object CodecUtils {
  def byteData: Array[Byte] =
    "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8)

  def mkData(size: Int): ByteBuffer = {
    val s = byteData
    val buff = ByteBuffer.allocate(size)
    while (buff.hasRemaining) buff.put(s, 0, math.min(buff.remaining(), s.length))
    buff.flip()
    buff
  }

  def zeroBuffer(size: Int): ByteBuffer = ByteBuffer.wrap(new Array(size))

  def compare(s1: Seq[ByteBuffer], s2: Seq[ByteBuffer]): Boolean = {
    val b1 = joinBuffers(s1)
    val b2 = joinBuffers(s2)
    b1.equals(b2)
  }

  class TestFrameDecoder(val listener: FrameListener)
      extends FrameDecoder(Http2Settings.default, listener)

  def decoder(h: FrameListener): TestFrameDecoder =
    new TestFrameDecoder(h)
}
