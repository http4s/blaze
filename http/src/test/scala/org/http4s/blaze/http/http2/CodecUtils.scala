package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.util.BufferTools._

import scala.concurrent.{Await, Awaitable}
import scala.concurrent.duration._

private[http2] object CodecUtils {

  def await[T](awaitable: Awaitable[T]): T = Await.result(awaitable, 5.seconds)

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
