package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.util.Locale

import org.http4s.blaze.http._
import org.http4s.blaze.http.http20.NodeMsg.Http2Msg
import org.http4s.blaze.pipeline.{Command => Cmd}
import org.http4s.blaze.pipeline.TailStage
import org.http4s.blaze.util.{BufferTools, Execution}
import Http2Exception.{INTERNAL_ERROR, PROTOCOL_ERROR}
import NodeMsg.{DataFrame, HeadersFrame}
import Http2StageTools._
import org.http4s.blaze.http.util.ServiceTimeoutFilter

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/** Basic implementation of a http2 stream [[TailStage]] */
// TODO: need to use the HttpServerConfig object
class Http2ServerStage(streamId: Int,
                       service: HttpService,
                       config: HttpServerConfig) extends TailStage[Http2Msg] {

  private implicit def _ec = Execution.trampoline   // for all the onComplete calls

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
    channelRead(timeout = config.requestPreludeTimeout).onComplete  {
      case Success(HeadersFrame(_, endStream, hs)) =>
        if (endStream) checkAndRunRequest(hs, MessageBody.emptyMessageBody)
        else getBodyReader(hs)

      case Success(frame) =>
        val e = PROTOCOL_ERROR(s"Received invalid frame: $frame", streamId, fatal = true)
        shutdownWithCommand(Cmd.Error(e))

        // TODO: what about a 408 response for a timeout?
      case Failure(Cmd.EOF) => shutdownWithCommand(Cmd.Disconnect)

      case Failure(t) =>
        logger.error(t)("Unknown error in startRequest")
        val e = INTERNAL_ERROR(s"Unknown error", streamId, fatal = true)
        shutdownWithCommand(Cmd.Error(e))
    }(Execution.directec)
  }

  private def getBodyReader(hs: Headers): Unit = {
    val length: Option[Either[String, Long]] = hs.collectFirst {
      case (ContentLength, v) =>
        try Right(java.lang.Long.valueOf(v))
        catch { case t: NumberFormatException =>
           Left(s"Invalid content-length: $v.")
        }
    }

    length match {
      case Some(Right(len)) => checkAndRunRequest(hs, new BodyReader(len))
      case Some(Left(error)) => shutdownWithCommand(Cmd.Error(PROTOCOL_ERROR(error, streamId, fatal = false)))
      case None => checkAndRunRequest(hs, new BodyReader(-1))
    }
  }

  private def checkAndRunRequest(hs: Headers, body: MessageBody): Unit = {

    val normalHeaders = new ArrayBuffer[(String, String)](hs.size)
    var method: String = null
    var scheme: String = null
    var path: String = null
    var error: String = ""
    var pseudoDone = false

    hs.foreach {
      case (Method, v)    =>
        if (pseudoDone) error += "Pseudo header in invalid position. "
        else if (method == null) method = v
        else error += "Multiple ':method' headers defined. "

      case (Scheme, v)    =>
        if (pseudoDone) error += "Pseudo header in invalid position. "
        else if (scheme == null) scheme = v
        else error += "Multiple ':scheme' headers defined. "

      case (Path, v)      =>
        if (pseudoDone) error += "Pseudo header in invalid position. "
        else if (path == null)   path   = v
        else error += "Multiple ':path' headers defined. "

      case (Authority, _) => // NOOP; TODO: we should keep the authority header
        if (pseudoDone) error += "Pseudo header in invalid position. "

      case h@(k, _) if k.startsWith(":") => error += s"Invalid pseudo header: $h. "
      case h@(k, _) if !validHeaderName(k) => error += s"Invalid header key: $k. "

      case hs =>    // Non pseudo headers
        pseudoDone = true
        hs match {
          case h@(Connection, _) => error += s"HTTP/2.0 forbids connection specific headers: $h. "

          case h@(TE, v) =>
            if (!v.equalsIgnoreCase("trailers")) error += s"HTTP/2.0 forbids TE header values other than 'trailers'. "
          // ignore otherwise

          case header => normalHeaders += header
      }
    }

    if (method == null || scheme == null || path == null) {
      error += s"Invalid request: missing pseudo headers. Method: $method, Scheme: $scheme, path: $path. "
    }

    if (error.length() > 0) shutdownWithCommand(Cmd.Error(PROTOCOL_ERROR(error, fatal = false)))
    else timeoutService(HttpRequest(method, path, 2, 0, hs, body))
      .onComplete(renderResponse(method, _))(config.serviceExecutor)
  }

  private def renderResponse(method: String, response: Try[ResponseBuilder]): Unit = response match {
    case Success(HttpResponse(builder)) =>
      builder.handle(getWriter(method == "HEAD", _))
        .onComplete(onComplete)(Execution.directec)

    case Success(t) =>
      val e = new Exception(s"Unhandled message type: $t")
      logger.error(e)("Message type not understoon")
      shutdownWithCommand(Cmd.Error(e))

    case Failure(t) => shutdownWithCommand(Cmd.Error(t))
  }

  private def getWriter(isHeadRequest: Boolean, prelude: HttpResponsePrelude): BodyWriter = {
    val hs = new ArrayBuffer[(String, String)](prelude.headers match {case b: IndexedSeq[_] => b.size + 1; case _ => 16 })
    hs += ((Status, Integer.toString(prelude.code)))
    prelude.headers.foreach{ case (k, v) => hs += ((k.toLowerCase(Locale.ROOT), v)) }

    val headersFrame = HeadersFrame(None, isHeadRequest, hs)

    if (isHeadRequest) new NoopWriter(headersFrame)
    else new StandardWriter(headersFrame)
  }

  private class StandardWriter(private var headers: HeadersFrame) extends BodyWriter {
    override type Finished = Unit

    private var closed = false
    private val lock = this

    override def flush(): Future[Unit] = lock.synchronized {
      if (closed) InternalWriter.closedChannelException
      else InternalWriter.cachedSuccess
    }

    override def write(buffer: ByteBuffer): Future[Unit] = lock.synchronized {
      if (closed) InternalWriter.closedChannelException
      else {
        if (buffer.hasRemaining) {
          val bodyFrame = DataFrame(false, buffer)
          if (headers != null) {
            val hs = headers
            headers = null
            channelWrite(hs :: bodyFrame :: Nil)
          }
          else channelWrite(bodyFrame)
        }
        else InternalWriter.cachedSuccess
      }
    }

    override def close(): Future[NoopWriter#Finished] = lock.synchronized {
      if (closed) InternalWriter.closedChannelException
      else {
        closed = true
        if (headers != null) {
          val hs = if (headers.endStream) headers else headers.copy(endStream = true)
          channelWrite(hs)
        }
        else channelWrite(DataFrame(true, BufferTools.emptyBuffer))
      }
    }
  }

  private def onComplete(result: Try[_]): Unit = result match {
    case Success(_)       => shutdownWithCommand(Cmd.Disconnect)
    case Failure(Cmd.EOF) => stageShutdown()
    case Failure(t)       => shutdownWithCommand(Cmd.Error(t))
  }

  ///////// BodyWriter's /////////////////////////////

  private class NoopWriter(headers: HeadersFrame) extends BodyWriter {
    override type Finished = Unit

    private var closed = false
    private val lock = this

    override def flush(): Future[Unit] = lock.synchronized {
      if (!closed) InternalWriter.cachedSuccess
      else InternalWriter.closedChannelException
    }

    override def write(buffer: ByteBuffer): Future[Unit] = flush()

    override def close(): Future[NoopWriter#Finished] = lock.synchronized {
      if (closed) InternalWriter.closedChannelException
      else {
        closed = true
        val hs = if (headers.endStream) headers else headers.copy(endStream = true)
        channelWrite(hs :: Nil)
      }
    }
  }

  private class BodyReader(length: Long) extends MessageBody {
    private var bytesRead = 0L
    private var finished = false

    private val lock = this

    def apply(): Future[ByteBuffer] = lock.synchronized {
      if (finished) MessageBody.emptyMessageBody()
      else {
        channelRead().flatMap( frame => lock.synchronized (frame match {
          case DataFrame(endStream, bytes, _) =>

            bytesRead += bytes.remaining()

            if (bytesRead > length) {
              // overflow. This is a stream error
              val msg = s"Invalid content-length, expected: $length, recieved (thus far): $bytesRead"
              val e = PROTOCOL_ERROR(msg, streamId, false)
              sendOutboundCommand(Cmd.Error(e))
              Future.failed(e)
            } else {
              finished = endStream
              Future.successful(bytes)
            }

          case HeadersFrame(_, endStream, ts) =>
            logger.warn(s"Discarding headers: $ts")
            if (endStream) {
              finished = true
              MessageBody.emptyMessageBody()
            }
            else {
              // trailing headers must be the end of the stream
              val msg = "Received trailing headers which didn't end the stream."
              val e = PROTOCOL_ERROR(msg, streamId, false)
              sendOutboundCommand(Cmd.Error(e))
              Future.failed(e)
            }

          case other =>
            finished = true
            val msg = "Received invalid frame while accumulating body: " + other
            logger.info(msg)
            val e = PROTOCOL_ERROR(msg, fatal = true)
            shutdownWithCommand(Cmd.Error(e))
            Future.failed(e)
        }))(Execution.trampoline)
      }
    }
  }
}


