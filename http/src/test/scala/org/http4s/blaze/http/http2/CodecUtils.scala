package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.util

import org.http4s.blaze.util.BufferTools._

object CodecUtils {
  def mkData(size: Int): ByteBuffer = {
    val s = "The quick brown fox jumps over the lazy dog".getBytes()
    val buff = ByteBuffer.allocate(size)
    while(buff.hasRemaining) buff.put(s, 0, math.min(buff.remaining(), s.length))
    buff.flip()
    buff
  }

  def compare(s1: Seq[ByteBuffer], s2: Seq[ByteBuffer]): Boolean = {
    val b1 = joinBuffers(s1)
    val b2 = joinBuffers(s2)
    b1.equals(b2)
  }

  class TestHttp2FrameDecoder(val listener: Http2FrameListener) extends Http2FrameDecoder(Http2Settings.default, listener)

  def decoder(h: Http2FrameListener, inHeaders: Boolean = false): TestHttp2FrameDecoder =
    new TestHttp2FrameDecoder(h)

  val bonusSize = 10

  def addBonus(buffers: Seq[ByteBuffer]): ByteBuffer = {
    joinBuffers(buffers :+ ByteBuffer.allocate(bonusSize))
  }
}
