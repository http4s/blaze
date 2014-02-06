package blaze.util

import blaze.channel.nio2.ClientChannelFactory
import blaze.pipeline.{Command, LeafBuilder, TailStage}
import java.nio.ByteBuffer
import scala.concurrent.{Promise, Future}
import java.nio.charset.StandardCharsets
import scala.util.{Success, Failure}
import blaze.http_parser.Http1ClientParser
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal
import scala.concurrent.duration.Duration
import java.net.InetSocketAddress
import scala.annotation.tailrec

/**
 * @author Bryce Anderson
 *         Created on 2/6/14
 */
object HttpClient {
  private implicit def ec =  Execution.trampoline

  case class Response(status: Int, reason: String, headers: Seq[(String, String)], body: Vector[ByteBuffer]) {
    def bodyString =  body.map(StandardCharsets.UTF_8.decode(_).toString).mkString
  }

  // This is a monstrosity born from a desire to quickly make a client
  private class ClientStage(method: String,
                            host: String,
                            uri: String,
                            headers: Seq[(String, String)],
                            body: ByteBuffer,
                            p: Promise[Response],
                            timeout: Duration = Duration.Inf)
                                extends Http1ClientParser with TailStage[ByteBuffer] {

    def name: String = "ClientStage"

    var code: Int = 0
    var reason: String = null
    var recvHdrs: List[(String, String)] = null
    var bodyBuffers = Vector.empty[ByteBuffer]

    val hdrs = new ListBuffer[(String, String)]




    // Methods for the Http1ClientParser -----------------------------------------------

    def submitResponseLine(code: Int, reason: String, scheme: String, majorversion: Int, minorversion: Int) {
      this.code = code
      this.reason = reason
    }

    def headerComplete(name: String, value: String): Boolean = {
      hdrs += ((name, value))
      false
    }

    // ---------------------------------------------------------------------------------

    // Entry method which, on startup, sends the request and attempts to parse the response
    override protected def stageStartup(): Unit = {
      super.stageStartup()

      mkRequest().onComplete {
        case Success(_) => parserLoop()
        case Failure(t) => p.failure(t)
      }
    }

    private def mkRequest(): Future[Any] = {
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

//      println(sb.result.replace("\r\n", "\\r\\n\r\n"))

      val hdr = ByteBuffer.wrap(sb.result().getBytes(StandardCharsets.US_ASCII))

      channelWrite(hdr::body::Nil, timeout)
    }

    private def parserLoop(): Unit = {
      channelRead(-1, timeout).onComplete {
        case Success(b) => parseBuffer(b)
        case Failure(t) => p.failure(t)
      }
    }

    private def parseBuffer(b: ByteBuffer): Unit = {
      try {             // this global try is killing a tailrec optimization...
        if (!this.responseLineComplete() && !parseResponseLine(b)) {
          parserLoop()
          return
        }
        if (!this.headersComplete() && !parseHeaders(b)) {
          parserLoop()
          return
        }

        // Must now be in body
        if (recvHdrs == null) {
          recvHdrs = hdrs.result()
          hdrs.clear()
        }

        @tailrec
        def parseBuffer(b: ByteBuffer): Unit = {
          val body = parseContent(b)

          //println("Received body: " +body)
          if (body != null) {

            //          println(s"$b, " + StandardCharsets.US_ASCII.decode(body.duplicate()))

            if (body.remaining() > 0)  bodyBuffers :+= body

            if (contentComplete()) {
              val r = Response(this.code, this.reason, this.recvHdrs, bodyBuffers)
              p.success(r)
            }
            else parseBuffer(b)  // We have sufficient data, but need to continue parsing. Probably chunking
          }
          else parserLoop()  // Need to get more data off the line
        }
        parseBuffer(b)

      }  // need more data
      catch { case NonFatal(t) => p.failure(t) }
    }
  }

  private lazy val connManager = new ClientChannelFactory()

  // TODO: perhaps we should really parse the URL
  private def parseURL(url: String): (String, Int, String, String) = {
    ("www.google.com", 80, "http", "/")
  }

  private def runReq(method: String,
                     url: String,
                     headers: Seq[(String, String)],
                     body: ByteBuffer,
                     timeout: Duration): Future[Response] = {

    val (host, port, scheme, uri) = parseURL(url)

    val p = Promise[Response]

    val fhead = connManager.connect(new InetSocketAddress(host, port))
    fhead.onComplete {
      case Success(head) =>
        val t = new ClientStage(method, host, uri, headers, BufferTools.emptyBuffer, p, timeout)
        LeafBuilder(t).base(head)
        head.sendInboundCommand(Command.Connect)

      case Failure(t) => p.failure(t)
    }

    p.future
  }

  def GET(url: String, headers: Seq[(String, String)], timeout: Duration = Duration.Inf): Future[Response] = {
    runReq("GET", url, headers, BufferTools.emptyBuffer, timeout)
  }

}
