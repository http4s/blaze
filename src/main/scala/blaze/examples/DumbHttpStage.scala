package blaze
package examples

import http_parser.Http1Parser
import pipeline.{TailStage, Command => Cmd}
import java.nio.ByteBuffer

import scala.util.{Success, Failure}

import util.Execution.trampoline

import scala.concurrent.Future
import scala.collection.mutable.ListBuffer

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */
class DumbHttpStage extends Http1Parser with TailStage[ByteBuffer] {

  private implicit def ec = trampoline

  val name = "DumbHttpStage"

  private def body = "ping\n"
  private def requestHeaders = "HTTP/1.1 200 OK\r\n" +
                               "Connection: Keep-Alive\r\n"

  private val full = requestHeaders + body

  logger.trace(s"DumbHttpStage starting up")

  private val fullresp = {
    val b = ByteBuffer.allocateDirect(full.length)
    b.put(full.getBytes())
    b.flip()
    b
  }
  val fullRespLimit = fullresp.limit()

  // Will act as our loop
  override def stageStartup() {
    logger.info("Starting pipeline")
    requestLoop()
  }

  private def requestLoop(): Unit = {
    channelRead().onComplete {
      case Success(buff) =>

        logger.trace{
          buff.mark()
          val sb = new StringBuilder
          println(buff)
          while(buff.hasRemaining) sb.append(buff.get().toChar)

          buff.reset()
          s"Received request\n${sb.result}"
        }

        try {
          if (!requestLineComplete() && !parseRequestLine(buff)) return requestLoop()
          if (!headersComplete() && !parseHeaders(buff)) return requestLoop()
          // we have enough to start the request
          runRequest(buff).onComplete {
            case Success(true)  => reset(); requestLoop()
            case o              =>
//              logger.info("Found other: " + o)
              stageShutdown()
              sendOutboundCommand(Cmd.Shutdown)
          }
        }
        catch { case t: Throwable   => stageShutdown() }

      case Failure(Cmd.EOF)    => stageShutdown()
      case Failure(t)          =>
        stageShutdown()
        sendOutboundCommand(Cmd.Error(t))
    }
  }

  private def runRequest(buffer: ByteBuffer): Future[Boolean] = {
//    val bb = new StringBuilder().append("You sent the following headers:\n")
//    headers.foreach{ case (n,v) => bb.append(n).append(": ").append(v).append("\n") }
//    val body = bb.result()

    val full = new StringBuilder().append(requestHeaders)
                  .append("Content-Length: ").append(body.length).append("\r\n\r\n")
                  .append(body)

    val buff = ByteBuffer.wrap(full.result.getBytes())

    val keepAlive: Boolean = {
      minor == 1 && keepAliveHeader.map(_._2.equalsIgnoreCase("Keep-Alive")).getOrElse(true)   ||
      minor == 0 && keepAliveHeader.map(_._2.equalsIgnoreCase("Keep-Alive")).getOrElse(false)
    }

    headers.clear()

    channelWrite(buff).flatMap(_ => drainBody(buffer)).map{_ =>
      uri = null         // zero out the state
      method = null
      minor = -1
      major = -1
      headers.clear()
      keepAlive
    }
  }

  private def drainBody(buffer: ByteBuffer): Future[Unit] = {
    if (!contentComplete()) {
      parseContent(buffer)
      channelRead().flatMap(drainBody)
    }
    else Future.successful()
  }

  private def keepAliveHeader = headers.find {
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
  
  private var uri: String = null
  private var method: String = null
  private var minor: Int = -1
  private var major: Int = -1
  private val headers = new ListBuffer[(String, String)]

  def submitRequestLine(methodString: String, uri: String, scheme: String, majorversion: Int, minorversion: Int) {
    logger.trace(s"Received request($methodString $uri $scheme/$majorversion.$minorversion)")
    this.uri = uri
    this.method = methodString
    this.major = majorversion
    this.minor = minorversion
  }

  def submitContent(buffer: ByteBuffer): Boolean = {
    // Don't care about content
    buffer.clear()
    true
  }
}
