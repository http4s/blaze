package org.http4s.blaze.channel.nio1

import java.nio.ByteBuffer

private trait Selectable {
  def opsReady(scratch: ByteBuffer): Unit
  def close(): Unit
  def closeWithError(cause: Throwable): Unit
}
