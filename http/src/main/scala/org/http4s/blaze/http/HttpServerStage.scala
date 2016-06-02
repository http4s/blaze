package org.http4s.blaze.http


import java.nio.charset.StandardCharsets

import org.http4s.blaze.pipeline.{Command => Cmd, _}
import org.http4s.blaze.util.Execution._
import org.http4s.websocket.WebsocketBits.WebSocketFrame
import org.http4s.websocket.WebsocketHandshake

import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}
import scala.concurrent.Future
import scala.collection.mutable.ArrayBuffer
import org.http4s.blaze.http.http_parser.Http1ServerParser
import org.http4s.blaze.http.http_parser.BaseExceptions.BadRequest
import org.http4s.blaze.http.websocket.WebSocketDecoder
import java.util.Date
import java.nio.ByteBuffer

import org.http4s.blaze.util.{BufferTools, Execution}

class HttpServerStage(maxReqBody: Long, maxNonbody: Int)(handleRequest: HttpService)
  extends Http1ServerParser(maxNonbody, maxNonbody, 5*1024) with TailStage[ByteBuffer] {
  import HttpServerStage._

  private implicit def ec = trampoline

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

      val req = Request(method, uri, hs, nextBodyBuffer)
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
    remainder = BufferTools.emptyBuffer
  }

  private def runRequest(request: Request): Unit = {
    try handleRequest(request) match {
      case HttpResponse(handler) => handler(getEncoder).onComplete(completionHandler)
      case WSResponse(stage) => handleWebSocket(request.headers, stage)
    }
    catch {
      case NonFatal(e) =>
        logger.error(e)("Error during `handleRequest` of HttpServerStage")
        //        val body = ByteBuffer.wrap("Internal Service Error".getBytes(StandardCharsets.ISO_8859_1))
        //        val resp = HttpResponse(500, "Internal Server Error", Nil, body)
        //
        //        handleHttpResponse(resp, reqHeaders, false).onComplete { _ =>
        shutdownWithCommand(Cmd.Error(e))
      //        }
    }
  }

  private def completionHandler(result: Try[Completed]): Unit = result match {
    case Success(completed) => completed.result match {
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

  private def getEncoder(prelude: HttpResponsePrelude): BodyWriter = {
    val forceClose = false // TODO: check for close headers, etc.
    val sb = new StringBuilder(512)
    sb.append("HTTP/").append(1).append('.').append(minor).append(' ')
      .append(prelude.code).append(' ')
      .append(prelude.status).append('\r').append('\n')

    val keepAlive = !forceClose && isKeepAlive(prelude.headers)

    if (!keepAlive) sb.append("Connection: close\r\n")
    else if (minor == 0 && keepAlive) sb.append("Connection: Keep-Alive\r\n")

    BodyWriter.selectWriter(prelude, sb, this)
  }

  /** Deal with route response of WebSocket form */
  private def handleWebSocket(reqHeaders: Headers, wsBuilder: LeafBuilder[WebSocketFrame]): Future[Completed] = {
    val sb = new StringBuilder(512)
    WebsocketHandshake.serverHandshake(reqHeaders) match {
      case Left((i, msg)) =>
        logger.info(s"Invalid handshake: $i: $msg")
        sb.append("HTTP/1.1 ").append(i).append(' ').append(msg).append('\r').append('\n')
          .append('\r').append('\n')

        channelWrite(ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.ISO_8859_1)))
          .map(_ => new Completed(Close))

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
          new Completed(Upgrade)
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
  private def nextBodyBuffer(): Future[ByteBuffer] = {
    if (contentComplete()) Future.successful(BufferTools.emptyBuffer)
    else channelRead().flatMap { nextBuffer =>
      remainder = BufferTools.concatBuffers(remainder, nextBuffer)
      val b = parseContent(remainder)

      if (b.hasRemaining || contentComplete()) Future.successful(b)
      else nextBodyBuffer() // Can't send an empty buffer unless we are finished.
    }(Execution.trampoline)
  }
}

private[http] object HttpServerStage {
  sealed trait RouteResult
  case object Reload  extends RouteResult
  case object Close   extends RouteResult
  case object Upgrade extends RouteResult
}
