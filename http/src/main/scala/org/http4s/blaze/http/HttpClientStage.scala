package org.http4s.blaze.http

import org.http4s.blaze.http.http_parser.Http1ClientParser
import org.http4s.blaze.pipeline.TailStage
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success}
import scala.annotation.tailrec
import scala.util.control.NonFatal
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.Duration

import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.channels.NotYetConnectedException

class HttpClientStage(timeout: Duration = Duration.Inf)
              (implicit val ec: ExecutionContext = Execution.trampoline)
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
                  timeout: Duration = Duration.Inf): Future[Response] = {

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

    val p = Promise[Response]
    channelWrite(hdr::body::Nil, timeout).onComplete {
      case Success(_) => parserLoop(p)
      case Failure(t) => p.failure(t)
    }

    p.future
  }

  private def parserLoop(p: Promise[Response]): Unit = {
    channelRead(timeout = timeout).onComplete {
      case Success(b) => parseBuffer(b, p)
      case Failure(t) => p.failure(t)
    }
  }

  private def parseBuffer(b: ByteBuffer, p: Promise[Response]): Unit = {
    try {
      if (!this.responseLineComplete() && !parseResponseLine(b)) {
        parserLoop(p)
        return
      }
      if (!this.headersComplete() && !parseHeaders(b)) {
        parserLoop(p)
        return
      }

      @tailrec
      def parseBuffer(b: ByteBuffer): Unit = {
        val body = parseContent(b)

        if (body != null) {

          if (body.remaining() > 0) {
            bodyBuffers += body.slice()
          }

          if (contentComplete()) {
            val b = BufferTools.joinBuffers(bodyBuffers.result())
            val r = SimpleHttpResponse(this.reason, this.code, hdrs.result(), b)
            reset()

            p.success(r)
          }
          else parseBuffer(b)  // We have sufficient data, but need to continue parsing. Probably chunking
        }
        else parserLoop(p)  // Need to get more data off the line
      }

      parseBuffer(b)
    } catch {
      case NonFatal(t) => p.failure(t)
    }
  }

}
