package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

// Place holder interface
trait WriteController {
  def write(data: Seq[ByteBuffer]): Unit

  def write(data: ByteBuffer): Unit
}
