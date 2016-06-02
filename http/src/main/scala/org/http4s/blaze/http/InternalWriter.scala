package org.http4s.blaze.http

import java.io.IOException
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.HttpServerStage.RouteResult
import org.log4s._

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

private abstract class InternalWriter extends BodyWriter {
  final override type Finished = RouteResult
}


private object InternalWriter {
  private val logger = getLogger

  val cachedSuccess = Future.successful(())
  def closedChannelException = Future.failed(new IOException("Channel closed"))
  val bufferLimit = 40*1024

  private sealed trait EncoderType
  private case object Undefined extends EncoderType
  private case object Chunked extends EncoderType
  private case class Static(length: Long) extends EncoderType

  def selectWriter(mustClose: Boolean, prelude: HttpResponsePrelude, sb: StringBuilder, stage: HttpServerStage): InternalWriter = {
    var forceClose = mustClose
    val hs = prelude.headers

    var encoderType: EncoderType = Undefined

    hs.foreach { case (k, v) =>
      if (k.equalsIgnoreCase("connection") && v.equalsIgnoreCase("close")) { forceClose = true }
      else if (k.equalsIgnoreCase("transfer-encoding") && v.equalsIgnoreCase("chunked")) { encoderType = Chunked }
      else if (k.equalsIgnoreCase("content-length")) Try(v.toLong) match {
        case Success(length) => encoderType = Static(length)
        case Failure(t) => logger.error(t)("Malformed 'content-length' header value")
      }
    }

    encoderType match {
      case Undefined => new SelectingWriter(forceClose, sb, stage)
      case Static(len) => new StaticBodyWriter(forceClose, len, stage)
      case Chunked =>
        val prelude = StandardCharsets.US_ASCII.encode(sb.append("\r\n").result())
        new ChunkedBodyWriter(forceClose, prelude, stage, -1)
    }
  }

  def renderHeaders(sb: StringBuilder, headers: Headers) {
    headers.foreach { case (k, v) =>
      // We are not allowing chunked responses at the moment, strip our Chunked-Encoding headers
      if (!k.equalsIgnoreCase("transfer-encoding") && !k.equalsIgnoreCase("content-length")) {
        sb.append(k)
        if (v.length > 0) sb.append(": ").append(v)
        sb.append('\r').append('\n')
      }
    }
  }

  def selectComplete(forceClose: Boolean, stage: HttpServerStage): RouteResult =
    if (forceClose || !stage.contentComplete()) HttpServerStage.Close
    else HttpServerStage.Reload
}
