package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer
import java.util.Locale

import org.http4s.blaze.http._
import org.http4s.blaze.http.http2.Http2Exception._
import org.http4s.blaze.http.http2.StageTools._
import org.http4s.blaze.http.http2.{HeadersFrame, StageTools, StreamMessage}
import org.http4s.blaze.http.util.ServiceTimeoutFilter
import org.http4s.blaze.pipeline.{TailStage, Command => Cmd}
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/** Basic implementation of a http2 stream [[TailStage]] */
private[http] class ServerStage(
  streamId: Int,
  service: HttpService,
  config: HttpServerStageConfig
) extends TailStage[StreamMessage] {

  private implicit def _ec = Execution.trampoline // for all the onComplete calls

  private val timeoutService = ServiceTimeoutFilter(config.serviceTimeout)(service)

  override def name = s"Http2StreamStage($streamId)"

  override protected def stageStartup(): Unit = {
    super.stageStartup()
    startRequest()
  }

  private def shutdownWithCommand(cmd: Cmd.OutboundCommand): Unit = {
    stageShutdown()
    sendOutboundCommand(cmd)
  }

  private def startRequest(): Unit = {
    // The prelude should already be available (or we wouldn't have a stream id)
    // so adding a timeout is unnecessary.
    channelRead().onComplete  {
      case Success(HeadersFrame(_, endStream, hs)) =>
        if (endStream) checkAndRunRequest(hs, BodyReader.EmptyBodyReader)
        else getBodyReader(hs)

      case Success(frame) =>
        val e = PROTOCOL_ERROR.goaway(
          s"Stream $streamId received invalid frame: ${frame.getClass.getSimpleName}")
        shutdownWithCommand(Cmd.Error(e))

        // TODO: what about a 408 response for a timeout?
      case Failure(Cmd.EOF) => shutdownWithCommand(Cmd.Disconnect)

      case Failure(t) =>
        logger.error(t)("Unknown error in startRequest")
        val e = INTERNAL_ERROR.rst(streamId, s"Unknown error")
        shutdownWithCommand(Cmd.Error(e))
    }(Execution.directec)
  }

  private[this] def getBodyReader(hs: Headers): Unit = {
    val length: Option[Try[Long]] = hs.collectFirst {
      case (ContentLength, v) =>
        try Success(java.lang.Long.valueOf(v))
        catch { case t: NumberFormatException =>
           Failure(PROTOCOL_ERROR.rst(streamId, s"Invalid content-length: $v."))
        }
    }

    length match {
      case Some(Success(len)) => checkAndRunRequest(hs, new BodyReaderImpl(len))
      case Some(Failure(error)) => shutdownWithCommand(Cmd.Error(error))
      case None => checkAndRunRequest(hs, new BodyReaderImpl(-1))
    }
  }

  private[this] def checkAndRunRequest(hs: Headers, bodyReader: BodyReader): Unit = {
    RequestParser.makeRequest(hs, bodyReader) match {
      case Right(request) =>
        timeoutService(request).onComplete(renderResponse(request.method, _))(config.serviceExecutor)

      case Left(errMsg) =>
        shutdownWithCommand(Cmd.Error(PROTOCOL_ERROR.rst(streamId, errMsg)))
    }
  }

  private[this] def renderResponse(method: String, response: Try[RouteAction]): Unit = response match {
    case Success(builder) =>
      builder.handle(getWriter(method, _))
        .onComplete(onComplete)(Execution.directec)

    case Failure(t) => shutdownWithCommand(Cmd.Error(t))
  }

  private[this] def getWriter(method: String, prelude: HttpResponsePrelude): BodyWriter = {
    val sizeHint = prelude.headers match {case b: IndexedSeq[_] => b.size + 1; case _ => 16 }
    val hs = new ArrayBuffer[(String, String)](sizeHint)
    hs += ((Status, Integer.toString(prelude.code)))

    StageTools.copyHeaders(prelude.headers, hs)
    // HEAD requests must not have a response body, so we ensure
    // that by using the `NoopWriter`, which only flushes the headers
    // and fails with an EOF for the `flush` and `write` operations.
    if (method == "HEAD") new NoopWriter(hs)
    else new StandardWriter(hs)
  }

  ///////// BodyWriter's /////////////////////////////

  private class StandardWriter(hs: Headers) extends AbstractBodyWriter(hs) {
    override protected def flushMessage(msg: StreamMessage): Future[Unit] =
      channelWrite(msg)

    override protected def flushMessage(msg: Seq[StreamMessage]): Future[Unit] =
      channelWrite(msg)
  }

  private def onComplete(result: Try[_]): Unit = result match {
    case Success(_)       => shutdownWithCommand(Cmd.Disconnect)
    case Failure(Cmd.EOF) => stageShutdown()
    case Failure(t)       => shutdownWithCommand(Cmd.Error(t))
  }

  private class NoopWriter(headers: Headers) extends BodyWriter {
    override type Finished = Unit

    private val underlying = new StandardWriter(headers)

    override def write(buffer: ByteBuffer): Future[Unit] = {
      underlying.close().flatMap { _ =>
        sendOutboundCommand(Cmd.Disconnect)
        InternalWriter.ClosedChannelException
      }
    }

    override def flush(): Future[Unit] = write(BufferTools.emptyBuffer)

    override def close(): Future[Unit] = underlying.close()
  }

  private class BodyReaderImpl(length: Long) extends AbstractBodyReader(streamId, length) {
    override protected def channelRead(): Future[StreamMessage] = ServerStage.this.channelRead()
    override protected def failed(ex: Throwable): Unit = sendOutboundCommand(Cmd.Error(ex))
  }
}
