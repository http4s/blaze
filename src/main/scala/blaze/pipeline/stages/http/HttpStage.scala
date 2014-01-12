package blaze.pipeline.stages.http

import java.nio.ByteBuffer
import blaze.pipeline.{Command => Cmd, TailStage}
import blaze.util.Execution._
import scala.util.{Failure, Success}
import scala.concurrent.Future
import scala.collection.mutable.ListBuffer
import blaze.http_parser.Http1Parser
import Http1Parser.ASCII

import blaze.http_parser.BaseExceptions.BadRequest

/**
 * @author Bryce Anderson
 *         Created on 1/11/14
 */

case class Response(status: String, code: Int, headers: Traversable[(String, String)], body: ByteBuffer)

abstract class HttpStage(maxReqBody: Int) extends Http1Parser with TailStage[ByteBuffer] {

  private implicit def ec = directec

  val name = "HttpStage"

  val emptyBuffer = ByteBuffer.allocate(0)

  private var uri: String = null
  private var method: String = null
  private var minor: Int = -1
  private var major: Int = -1
  private val headers = new ListBuffer[(String, String)]
  
  def handleRequest(method: String, uri: String, headers: Traversable[(String, String)], body: ByteBuffer): Future[Response]
  
  /////////////////////////////////////////////////////////////////////////////////////////

  // Will act as our loop
  override def stageStartup() {
    logger.info("Starting HttpStage")
    requestLoop()
  }

  private def requestLoop(): Unit = {
    channelRead().onComplete {
      case Success(buff) =>

        logger.trace{
          buff.mark()
          val sb = new StringBuilder

          while(buff.hasRemaining) sb.append(buff.get().toChar)

          buff.reset()
          s"RequestLoop received buffer $buff. Request:\n${sb.result}"
        }

        try {
          if (!requestLineComplete() && !parseRequestLine(buff)) return requestLoop()
          if (!headersComplete() && !parseHeaders(buff)) return requestLoop()
          // we have enough to start the request
          gatherBody(buff, emptyBuffer).onComplete{
            case Success(b) => runRequest(b)
            case Failure(t) => sendOutboundCommand(Cmd.Shutdown)
          }
        }
        catch { case t: Throwable   => sendOutboundCommand(Cmd.Shutdown) }

      case Failure(Cmd.EOF)    => sendOutboundCommand(Cmd.Shutdown)
      case Failure(t)          =>
        stageShutdown()
        sendOutboundCommand(Cmd.Error(t))
    }
  }

  private def resetStage() {
    reset()
    uri = null
    method = null
    minor = -1
    major = -1
    headers.clear()
  }

  private def runRequest(buffer: ByteBuffer): Unit = {

    val fr = handleRequest(method, uri, headers, buffer)

    val keepAlive: Boolean = {
      val h = connectionHeader
      if (h.isDefined) {
        if (h.get._2.equalsIgnoreCase("Keep-Alive")) true
        else if (h.get._2.equalsIgnoreCase("close")) false
        else { logger.info(s"Bad Connection header value: '${h.get._2}'. Closing after request."); false }
      }
      else if (minor == 0) false
      else true
    }

    headers.clear()

    fr.flatMap{ resp =>
      val sb = new StringBuilder(512)
      sb.append("HTTP/").append(1).append('.')
        .append(minor).append(' ').append(resp.code)
        .append(' ').append(resp.status).append('\r').append('\n')

      if (!keepAlive) sb.append("Connection: close\r\n")
      else if (minor == 0 && keepAlive) sb.append("Connection: Keep-Alive\r\n")

      renderHeaders(sb, resp.headers, resp.body.remaining())

      val messages = new Array[ByteBuffer](2)
      messages(0) = ByteBuffer.wrap(sb.result().getBytes(ASCII))
      messages(1) = resp.body

      channelWrite(messages)
    }.onComplete {       // See if we should restart the loop
      case Success(_) if keepAlive  => resetStage(); requestLoop()
      case Failure(t: BadRequest)   => badRequest(t)
      case o                        =>
        //              logger.info("Found other: " + o)
        sendOutboundCommand(Cmd.Shutdown)
    }
  }

  private def badRequest(msg: BadRequest) {
    val sb = new StringBuilder(512)
    sb.append("HTTP/").append(1).append('.')
      .append(minor).append(' ').append(400)
      .append(' ').append("Bad Request").append('\r').append('\n').append('\r').append('\n')

    channelWrite(ByteBuffer.wrap(sb.result().getBytes(ASCII)))
      .onComplete(_ => sendOutboundCommand(Cmd.Shutdown))
  }

  private def renderHeaders(sb: StringBuilder, headers: Traversable[(String, String)], length: Int) {
    headers.foreach{ case (k, v) =>
      // We are not allowing chunked responses at the moment, strip our Chunked-Encoding headers
      if (!k.equalsIgnoreCase("Transfer-Encoding") && !k.equalsIgnoreCase("Content-Length")) {
        sb.append(k)
        if (v.length > 0) sb.append(": ").append(v).append('\r').append('\n')
      }
    }
    // Add our length header last
    sb.append(s"Content-Length: ").append(length).append('\r').append('\n')
    sb.append('\r').append('\n')
  }

  private def gatherBody(buffer: ByteBuffer, cumulative: ByteBuffer): Future[ByteBuffer] = {
    if (!contentComplete()) {
      val next = parseContent(buffer)
      if (cumulative.remaining() > next.remaining()) {     // Still room
        cumulative.put(next)
        channelRead().flatMap(gatherBody(_, cumulative))
      }
      else {
        cumulative.flip()
        val n = ByteBuffer.allocate(2*(next.remaining() + cumulative.remaining()))
        n.put(cumulative).put(next)
        channelRead().flatMap(gatherBody(_, n))
      }
    }
    else {
      cumulative.flip()
      Future.successful(cumulative)
    }
  }

  private def connectionHeader = headers.find {
    case ("Connection", _) => true
    case _ => false
  }

  override protected def stageShutdown(): Unit = {
    logger.info("Shutting down HttpPipeline")
    shutdownParser()
    super.stageShutdown()
  }

  def headerComplete(name: String, value: String) = {
    logger.trace(s"Received header '$name: $value'")
    headers += ((name, value))
  }

  def submitRequestLine(methodString: String, uri: String, scheme: String, majorversion: Int, minorversion: Int) {
    logger.trace(s"Received request($methodString $uri $scheme/$majorversion.$minorversion)")
    this.uri = uri
    this.method = methodString
    this.major = majorversion
    this.minor = minorversion
  }

}
