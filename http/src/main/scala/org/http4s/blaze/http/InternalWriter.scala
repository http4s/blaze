package org.http4s.blaze.http

import java.io.IOException
import org.http4s.blaze.http.HttpCodec.RouteResult
import org.log4s._
import scala.concurrent.Future

private abstract class InternalWriter extends BodyWriter {
  final override type Finished = RouteResult
}


private object InternalWriter {
  private val logger = getLogger

  val cachedSuccess = Future.successful(())
  def closedChannelException = Future.failed(new IOException("Channel closed"))
  val bufferLimit = 32*1024

  def selectComplete(forceClose: Boolean): RouteResult =
    if (forceClose) HttpCodec.Close
    else HttpCodec.Reload
}
