package org.http4s.blaze.http

import org.http4s.blaze.pipeline.Command
import org.http4s.blaze.util.{FutureEOF, FutureUnit}

import scala.concurrent.Future

private object InternalWriter {
  val CachedSuccess = FutureUnit
  val ClosedChannelException = FutureEOF
  val BufferLimit = 32 * 1024
}
