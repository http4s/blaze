package org.http4s.blaze.http


import java.nio.charset.StandardCharsets

import org.http4s.blaze.pipeline.{Command => Cmd, _}
import org.http4s.blaze.util.Execution._
import org.http4s.websocket.WebsocketBits.WebSocketFrame
import org.http4s.websocket.WebsocketHandshake

import scala.util.{Failure, Success, Try}
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.mutable.ArrayBuffer
import org.http4s.blaze.http.http_parser.Http1ServerParser
import org.http4s.blaze.http.http_parser.BaseExceptions.BadRequest
import org.http4s.blaze.http.websocket.WebSocketDecoder
import java.util.Date
import java.nio.ByteBuffer

import org.http4s.blaze.util.{BufferTools, Execution}

import scala.annotation.tailrec

class HttpServerStage(maxReqBody: Long, maxNonBody: Int, ec: ExecutionContext)(handleRequest: HttpService)
  extends Http1ServerParser(maxNonBody, maxNonBody, 5*1024) with TailStage[ByteBuffer] { httpServerStage =>
  import HttpServerStage._

  private implicit def implicitEC = trampoline

  val name = "HTTP/1.1_Stage"

  private var uri: String = null
  private var method: String = null
  private var minor: Int = -1
  private var major: Int = -1
  private var headers = new ArrayBuffer[(String, String)]
  private var remainder: ByteBuffer = BufferTools.emptyBuffer
  
  /////////////////////////////////////////////////////////////////////////////////////////

  // Will act as our loop
  override def stageStartup() {
    logger.info("Starting HttpStage")
    requestLoop()
  }

  private def requestLoop(): Unit = {
    channelRead().onComplete {
      case Success(buff)    => readLoop(buff)
      case Failure(Cmd.EOF) => // NOOP
      case Failure(t)       => shutdownWithCommand(Cmd.Error(t))
    }
  }

  private def readLoop(buff: ByteBuffer): Unit = {
    try {
      if (!requestLineComplete() && !parseRequestLine(buff)) {
        requestLoop()
        return
      }

      if (!headersComplete() && !parseHeaders(buff)) {
        requestLoop()
        return
      }

      // TODO: need to check if we need a Host header or otherwise validate the request
      // we have enough to start the request
      val hs = headers
      headers = new ArrayBuffer[(String, String)](hs.size + 10)

      remainder = buff

      val req = HttpRequest(method, uri, hs, getMessageBody())
      runRequest(req)
    }
    catch { case t: Throwable => shutdownWithCommand(Cmd.Error(t)) }
  }

  private def resetStage() {
    reset()
    uri = null
    method = null
    minor = -1
    major = -1
    headers.clear()
  }

  private def runRequest(request: HttpRequest): Unit = {
    try handleRequest(request).onComplete {
      case Success(HttpResponse(handler)) =>

        val forceClose = request.headers.exists { case (k, v) =>
          k.equalsIgnoreCase("connection") && v == "close"
        }

        try handler.handle(getEncoder(forceClose, _)).onComplete(completionHandler)
        catch { case e: Throwable =>
          logger.error(e)("Failure during response encoding. Response not committed.")
          completionHandler(Failure(e))
        }

      case Success(WSResponseBuilder(stage)) => handleWebSocket(request.headers, stage)
      case Failure(e) => send500(e)
    }(ec)
    catch { case e: Throwable => send500(e) }
  }

  private def send500(error: Throwable): Unit = {
    logger.error(error)("Failed to select a response. Sending 500 response.")
    val body = ByteBuffer.wrap("Internal Service Error".getBytes(StandardCharsets.ISO_8859_1))

    val errorResponse = RouteAction.byteBuffer(500, "Internal Server Error", Nil, body)
    errorResponse.action.handle(getEncoder(true, _)).onComplete { _ =>
      shutdownWithCommand(Cmd.Error(error))
    }
  }

  private def completionHandler(result: Try[RouteResult]): Unit = result match {
    case Success(completed) =>
      completed match {
      case Close           => shutdownWithCommand(Cmd.Disconnect)
      case Upgrade         => // NOOP: don't need to do anything
      case Reload          =>
        val buff = remainder
        resetStage()
        readLoop(buff)
    }
    case Failure(t: BadRequest)   => badRequest(t)
    case Failure(t)               => shutdownWithCommand(Cmd.Error(t))
  }

  private def getEncoder(forceClose: Boolean, prelude: HttpResponsePrelude): InternalWriter = {
    val sb = new StringBuilder(512)
    sb.append("HTTP/").append(1).append('.').append(minor).append(' ')
      .append(prelude.code).append(' ')
      .append(prelude.status).append('\r').append('\n')

    val keepAlive = !forceClose && isKeepAlive(prelude.headers)

    if (!keepAlive) sb.append("connection: close\r\n")
    else if (minor == 0 && keepAlive) sb.append("Connection: Keep-Alive\r\n")

    InternalWriter.selectWriter(!keepAlive, prelude, sb, this)
  }

  /** Deal with route response of WebSocket form */
  private def handleWebSocket(reqHeaders: Headers, wsBuilder: LeafBuilder[WebSocketFrame]): Future[RouteResult] = {
    val sb = new StringBuilder(512)
    WebsocketHandshake.serverHandshake(reqHeaders) match {
      case Left((i, msg)) =>
        logger.info(s"Invalid handshake: $i: $msg")
        sb.append("HTTP/1.1 ").append(i).append(' ').append(msg).append('\r').append('\n')
          .append('\r').append('\n')

        channelWrite(ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.ISO_8859_1)))
          .map(_ => Close)

      case Right(hdrs) =>
        logger.info("Starting websocket request")
        sb.append("HTTP/1.1 101 Switching Protocols\r\n")
        hdrs.foreach { case (k, v) => sb.append(k).append(": ").append(v).append('\r').append('\n') }
        sb.append('\r').append('\n')

        // write the accept headers and reform the pipeline
        channelWrite(ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.ISO_8859_1))).map{ _ =>
          logger.debug("Switching pipeline segments for upgrade")
          val segment = wsBuilder.prepend(new WebSocketDecoder(false))
          this.replaceInline(segment)
          Upgrade
        }
    }
  }

  private def badRequest(msg: BadRequest): Unit = {
    val sb = new StringBuilder(512)
    sb.append("HTTP/").append(1).append('.')
      .append(minor).append(' ').append(400)
      .append(" Bad Request\r\n\r\n")

    channelWrite(ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.ISO_8859_1)))
      .onComplete(_ => shutdownWithCommand(Cmd.Disconnect))
  }

  private def shutdownWithCommand(cmd: Cmd.OutboundCommand): Unit = {
    stageShutdown()
    sendOutboundCommand(cmd)
  }

  private def isKeepAlive(headers: Headers): Boolean = {
    val h = headers.find {
      case (k, _) if k.equalsIgnoreCase("connection") => true
      case _                                          => false
    }

    h match {
      case Some((k, v)) =>
        if (v.equalsIgnoreCase("Keep-Alive")) true
        else if (v.equalsIgnoreCase("close")) false
        else if (v.equalsIgnoreCase("Upgrade")) true
        else {
          logger.info(s"Bad Connection header value: '$v'. Closing after request.")
          false
        }

      case None if minor == 0 => false
      case None               => true
    }
  }

  override protected def stageShutdown(): Unit = {
    logger.info("Shutting down HttpPipeline at " + new Date())
    shutdownParser()
  }

  protected def headerComplete(name: String, value: String): Boolean = {
    headers += ((name, value))
    false
  }

  protected def submitRequestLine(methodString: String,
                                  uri: String,
                                  scheme: String,
                                  majorversion: Int,
                                  minorversion: Int): Boolean = {
    this.uri = uri
    this.method = methodString
    this.major = majorversion
    this.minor = minorversion
    false
  }

  // Gets the next body buffer from the line
  private def getMessageBody() = new MessageBody {

    override def apply(): Future[ByteBuffer] = httpServerStage.synchronized {
      @tailrec
      def go(): Future[ByteBuffer] = {
        if (contentComplete()) Future.successful(BufferTools.emptyBuffer)
        else if (remainder.hasRemaining()) parseContent(remainder) match {
          case null => readChunk()
          case buff if !buff.hasRemaining => go()
          case buff => Future.successful(buff)
        }
        else readChunk()
      }

      go()
    }

    private def readChunk(): Future[ByteBuffer] = channelRead().flatMap(nextBuffer => httpServerStage.synchronized {
        remainder = BufferTools.concatBuffers(remainder, nextBuffer)
      apply()
    })(Execution.trampoline)
  }
}

private[http] object HttpServerStage {
  sealed trait RouteResult
  case object Reload  extends RouteResult
  case object Close   extends RouteResult
  case object Upgrade extends RouteResult
}
