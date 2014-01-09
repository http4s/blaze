package blaze.examples

import java.nio.ByteBuffer

import scala.util.{Success, Failure}
import scala.annotation.tailrec
import blaze.http_parser.Http1Parser
import blaze.pipeline.TailStage

import blaze.util.Execution.directec
import blaze.pipeline.{Command => Cmd}
import blaze.http_parser.BaseExceptions.BadRequest

import org.http4s._
import scala.collection.mutable.ListBuffer
import org.http4s.Header.RawHeader

import scalaz.{-\/, \/-, \/}
import scala.concurrent.Future
import scalaz.concurrent.Task
import scalaz.stream.Process
import Process.End


/**
 * @author Bryce Anderson
 *         Created on 1/5/14
 */
abstract class Http4sStage(maxReqLen: Int = 2048,
                  maxHeaderLength: Int = 40*1024,
                  initialBufferSize: Int = 10*1024,
                  maxChunkSize: Int = Integer.MAX_VALUE)
      extends Http1Parser(maxReqLen, maxHeaderLength, initialBufferSize, maxChunkSize) with TailStage[ByteBuffer] {

  private implicit def ec = directec

  val name = "Http4sStage"

  private val headers = new ListBuffer[Header]

  private var closeOnFinish = false
  private var method: String = null
  private var uri: String = null
  private var protocol: ServerProtocol = null
  private var storedBuffer: ByteBuffer = null


  override protected def shutdown(): Unit = {
    logger.trace("Shutting down HttpPipeline")

    reset()
    super.shutdown()
  }

  protected def getRequest(): Future[Request] = {
    assert(!requestLineComplete())

    channelRead().flatMap{ buffer =>
      if (parseRequestLine(buffer)) getHeaders(buffer)
      else getRequest()
    }
  }

  // Once we get here, the request line bits should be populated
  private def getHeaders(buffer: ByteBuffer): Future[Request] = {
    assert(requestLineComplete() && !headersComplete())

    if (!buffer.hasRemaining) channelRead().flatMap(getHeaders)
    else {
      if (parseHeaders(buffer)) { // Collected all the data we need
        this.storedBuffer = buffer
        val t = Task.async[BodyChunk](cb =>
         requestBodyChunk.onComplete {
           case Success(c) => cb(\/-(c))
           case Failure(t) => cb(-\/(t))
         })

        val h = HeaderCollection(headers.result)
        headers.clear()
        val body: HttpBody = Process.repeatEval(t) // Process[Task, Chunk]

        Future.successful(Request(Method.resolve(method), Uri.fromString(uri), protocol, h, body))
      }
      else channelRead().flatMap(getHeaders) // need more data
    }
  }

  private def requestBodyChunk: Future[BodyChunk] = {
    if (!contentComplete()) {
      val result = parseContent(this.storedBuffer)
      ???
    }
    else Future.failed(End)
  }


  def stageReset(): Unit = {
    reset()   // reset the parser

    headers.clear()
    closeOnFinish = false
    method = null
    uri = null
    protocol = null
  }

  def headerComplete(name: String, value: String) = {
    logger.trace(s"Received header '$name: $value'")


    // See if it is an important header
    if (name.equalsIgnoreCase("Connection")) {   // Connection header
      headers += Header.Connection(value)
      if (value.equalsIgnoreCase("close")) closeOnFinish = true
      else if (value.equalsIgnoreCase("Keep-Alive")) closeOnFinish = false
    }
    else {
      val header = Header(name, value)
      headers += header
    }
  }

  def requestLineComplete(met: String, uri: String, sch: String, maj: Int, min: Int): Unit = {
    logger.trace(s"Received request($met $uri $sch/$maj.$min)")

    // Save the important parts
    this.method = met
    this.uri = uri
    if (min == 1) this.protocol = ServerProtocol.`HTTP/1.1`
    else {
      closeOnFinish = true
      ServerProtocol.`HTTP/1.0`
    }
  }

  def submitContent(buffer: ByteBuffer): Boolean = {
    // Don't care about content
    buffer.clear()
    false   // Wait for another request before asking for more content
  }
}
