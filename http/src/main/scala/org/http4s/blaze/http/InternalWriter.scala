package org.http4s.blaze.http

import java.io.IOException
import scala.concurrent.Future


private object InternalWriter {
  val cachedSuccess = Future.successful(())
  def closedChannelException = Future.failed(new IOException("Channel closed"))
  val bufferLimit = 32*1024
}
