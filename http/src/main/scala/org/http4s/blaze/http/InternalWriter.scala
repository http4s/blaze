package org.http4s.blaze.http

import org.http4s.blaze.pipeline.Command

import scala.concurrent.Future

private object InternalWriter {
  val CachedSuccess = Future.successful(())
  val ClosedChannelException = Future.failed(Command.EOF)
  val BufferLimit = 32 * 1024
}
