package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.specs2.mutable.Specification

class ByteBufferInputStreamSpec extends Specification {

  private def fromByteBuffer(buffer: ByteBuffer): ByteBufferInputStream =
    new ByteBufferInputStream(buffer)

  private def fromBytes(bytes: Byte*): ByteBufferInputStream =
    fromByteBuffer(ByteBuffer.wrap(bytes.toArray))

  "ByteBufferInputStream" should {
    "report available bytes" in {
      forall(0 until 10) { i =>
        val bb = ByteBuffer.wrap(new Array[Byte](i))
        fromByteBuffer(bb).available() must_== i
      }
    }

    "read -1 when bytes unavailable" in {
      fromBytes().read() must_== -1
    }

    "read byte when available" in {
      val range = 0 until 10
      val is = fromBytes(range.map(_.toByte): _*)
      forall(range) { i =>
        is.read() must_== i
      }

      is.available() must_== 0
      is.read() must_== -1
    }

    "handle mark and reset apporpriately" in {
      val is = fromBytes((0 until 10).map(_.toByte): _*)

      is.markSupported must beTrue

      is.read() must_== 0
      is.mark(1)
      is.available() must_== 9
      is.read() must_== 1
      is.available() must_== 8
      is.reset()
      is.available() must_== 9
      is.read() must_== 1
      is.available() must_== 8
    }
  }
}
