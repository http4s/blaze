package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

object HeaderCodecHelpers {
  def encodeHeaders(hs: Seq[(String, String)], maxTableSize: Int): ByteBuffer = {
    val enc = new HeaderEncoder(maxTableSize)
    enc.encodeHeaders(hs)
  }

  def decodeHeaders(bb: ByteBuffer, maxTableSize: Int): Seq[(String, String)] = {
    val dec = new HeaderDecoder(Int.MaxValue, false, maxTableSize)
    dec.decode(bb, -1, true)
    dec.finish()
  }
}
