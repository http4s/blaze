package blaze
package examples

import http_parser.RequestParser
import pipeline.{TailStage, Command => Cmd}
import java.nio.ByteBuffer

import scala.util.{Success, Failure}
import scala.annotation.tailrec

import util.DirectExecutor.direct

import http_parser.BaseExceptions.{BadRequest, ParsingError}

/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */
class DumbHttpStage extends RequestParser with TailStage[ByteBuffer] {
  val name = "DumbHttpStage"

  private def body = "ping\n"
  private def headers = "HTTP/1.1 200 OK\r\n" +
                        "Connection: Keep-Alive\r\n" +
                        "Content-Length: " + body.length + "\r\n" +
                        "\r\n"

  private val full = headers + body

  logger.info(s"Full request: $full")

  private val fullresp = {
    val b = ByteBuffer.allocateDirect(full.length)
    b.put(full.getBytes())
    b.flip()
    b
  }
  val fullRespLimit = fullresp.limit()

  private var inloop =  false

  private var closeOnFinish = false

  // Will act as our loop
  override def startup() {
    assert(!inloop)
    inloop = true
    parseLoop()
  }

  private def parseLoop() {
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


        try parseBuffer(buff)
        catch {
          case e: ParsingError => earlyEOF()
          case r: BadRequest   => earlyEOF()
        }

      case Failure(Cmd.EOF)    => shutdown()
      case Failure(t)          =>
        shutdown()
        sendOutboundCommand(Cmd.Error(t))
    }
  }

  @tailrec
  private def parseBuffer(buff: ByteBuffer): Unit = {
    
    if (!inloop) return
    
    if (buff.remaining() < 1 && !finished()) { // Do we need more data?
      parseLoop()
      return
    }

    if (inRequestLine && parseRequestLine(buff)) {
      logger.trace("Parsing request line")
      parseBuffer(buff)
    }
    else if (inHeaders() && parseHeaders(buff)) {
      logger.trace("---------------- Parsing headers")
      parseBuffer(buff)
    }
    else if (inContent() && parseContent(buff)) {
      logger.trace("---------------- Parsing content")
      parseContent(buff)
    }
    else if (finished()) {
      logger.trace("---------------- Request Finished")
      if (buff.hasRemaining) logger.warn(s"Request finished, but there is buffer remaining")
      reset()
      if (closeOnFinish) {
        shutdown()
        sendOutboundCommand(Cmd.Shutdown)
      }
      else channelWrite(fullresp).onComplete{
        case Success(_) =>
          fullresp.position(0)
          logger.trace(s"Wrote bytes to channel. Remaining buffer: $buff")
          parseLoop()

        case Failure(t) => outboundCommand(Cmd.Error(t))
      }
    }
     // inconsistent state!
    else sys.error("Inconsistent state")
  }

  override protected def shutdown(): Unit = {
    logger.trace("Shutting down HttpPipeline")
    inloop = false
    super.shutdown()
  }

  def earlyEOF(): Unit = sendOutboundCommand(Cmd.Shutdown)

  def headerComplete(name: String, value: String) = {
    //logger.trace(s"Received header '$name: $value'")
  }

  def requestComplete() {
    logger.trace("------------- Request completed.")
  }
  
  def headersComplete() {
    logger.trace("--------------Headers completed.")
  }

  def startRequest(methodString: String, uri: String, scheme: String, majorversion: Int, minorversion: Int): Boolean = {
    //if (minorversion == 0) closeOnFinish = true

    logger.trace(s"Received request($methodString $uri $scheme/$majorversion.$minorversion)");
    true
  }

  def submitContent(buffer: ByteBuffer): Boolean = {
    // Don't care about content
    buffer.clear()
    true
  }
}
