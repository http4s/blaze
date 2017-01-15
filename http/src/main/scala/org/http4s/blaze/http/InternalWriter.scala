package org.http4s.blaze.http

import java.io.IOException
import org.http4s.blaze.http.HttpServerCodec.RouteResult
import org.log4s._
import scala.concurrent.Future


private object InternalWriter {
  private val logger = getLogger

  val cachedSuccess = Future.successful(())
  def closedChannelException = Future.failed(new IOException("Channel closed"))
  val bufferLimit = 32*1024

  def selectComplete(forceClose: Boolean): RouteResult =
    if (forceClose) HttpServerCodec.Close
    else HttpServerCodec.Reload
}
