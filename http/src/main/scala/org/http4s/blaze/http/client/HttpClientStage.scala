package org.http4s.blaze.http.client

import java.nio.ByteBuffer
import java.nio.channels.NotYetConnectedException
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.parser.Http1ClientParser
import org.http4s.blaze.pipeline.TailStage
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class HttpClientStage(timeout: Duration)
    extends Http1ClientParser with TailStage[ByteBuffer] {

  def name: String = "ClientStage"

  @volatile private var connected = false

  private var code: Int = 0
  private var reason: String = null
  private val bodyBuffers = new ListBuffer[ByteBuffer]

  private val hdrs = new ListBuffer[(String, String)]


  override protected def reset() {
    super.reset()
    code = 0
    reason = null
    bodyBuffers.clear()
    hdrs.clear()
  }

  // Methods for the Http1ClientParser -----------------------------------------------

  protected def submitResponseLine(code: Int,
                                 reason: String,
                                 scheme: String,
                           majorversion: Int,
                           minorversion: Int) {
    this.code = code
    this.reason = reason
  }

  protected def headerComplete(name: String, value: String): Boolean = {
    hdrs += ((name, value))
    false
  }

  // ---------------------------------------------------------------------------------

  // Entry method which, on startup, sends the request and attempts to parse the response
  override protected def stageStartup(): Unit = {
    super.stageStartup()
    connected = true
  }

  override protected def stageShutdown(): Unit = {
    super.stageShutdown()
    connected = false
  }

  def makeRequest(method: String,
                  host: String,
                  uri: String,
                  headers: Seq[(String, String)],
                  body: ByteBuffer,
                  timeout: Duration = Duration.Inf): Future[ClientResponse] = {

    if (!connected) return Future.failed(new NotYetConnectedException)

    val sb = new StringBuilder(256)

    sb.append(method).append(' ').append(uri).append(' ').append("HTTP/1.1\r\n")
      .append("Host: ").append(host).append("\r\n")

    headers.foreach{ case (k, v) =>
      sb.append(k)
      if (v.length > 0) sb.append(": ").append(v)
      sb.append("\r\n")
    }

    if (body.remaining() > 0 || method.equals("PUT") || method.equals("POST")) {
      sb.append("Content-Length: ").append(body.remaining()).append("\r\n")
    }

    sb.append("\r\n")

    val hdr = ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.ISO_8859_1))

    val p = Promise[ClientResponse]
    channelWrite(hdr::body::Nil, timeout).onComplete {
      case Success(_) => parserLoop(p)
      case Failure(t) => p.failure(t)
    }(Execution.directec)

    p.future
  }

  private def parserLoop(p: Promise[ClientResponse]): Unit = {
    channelRead(timeout = timeout).onComplete {
      case Success(b) => parseBuffer(b, p)
      case Failure(t) => p.failure(t)
    }(Execution.trampoline)
  }

  private def parseBuffer(b: ByteBuffer, p: Promise[ClientResponse]): Unit = {
    try {
      if (!this.responseLineComplete() && !parseResponseLine(b)) {
        parserLoop(p)
        return
      }
      if (!this.headersComplete() && !parseHeaders(b)) {
        parserLoop(p)
        return
      }

      p.trySuccess {
        if (contentComplete()) {
          ClientResponse(this.code, this.reason, hdrs.result(), () => Future.successful(BufferTools.emptyBuffer))
        } else {

          @volatile var buffer = b
          // Reads the body buffers until completion.
          def next(): Future[ByteBuffer] = {
            if (contentComplete()) Future.successful(BufferTools.emptyBuffer)
            else if (buffer.hasRemaining()) {
              val n1 = parseContent(buffer)
              if (n1 != null) Future.successful(n1)
              else next()
            }
            else {
              channelRead(timeout = timeout).flatMap { buff =>
                buffer = buff
                next()
              }(Execution.trampoline)
            }
          }

          ClientResponse(this.code, this.reason, hdrs.result(), next)
        }
      }
    } catch {
      case NonFatal(t) => p.tryFailure(t)
    }
  }

}
