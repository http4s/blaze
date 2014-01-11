package org.http4s.blaze

import blaze.http_parser.Http1Parser
import blaze.pipeline.{Command => Cmd}
import blaze.pipeline.TailStage
import blaze.util.Execution._

import java.nio.ByteBuffer
import scala.concurrent.Future
import scala.collection.mutable.ListBuffer
import scala.util.Success
import scala.util.Failure
import org.http4s._

import scalaz.stream.Process
import scalaz.concurrent.Task
import Process._
import scalaz.{\/-, -\/}
import org.http4s.util.StringWriter

/**
 * @author Bryce Anderson
 *         Created on 1/10/14
 */
class Http4sStage(route: HttpService) extends Http1Parser with TailStage[ByteBuffer] {

  protected implicit def ec = directec

  val name = "Http4sStage"

  private var uri: String = null
  private var method: String = null
  private var minor: Int = -1
  private var major: Int = -1
  private val headers = new ListBuffer[Header]

  logger.trace(s"Http4sStage starting up")

  // Will act as our loop
  override def stageStartup() {
    logger.info("Starting pipeline")
    requestLoop()
  }

  private def requestLoop(): Unit = {
    channelRead().onComplete {
      case Success(buff) =>

        logger.trace {
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
          runRequest(buff)
        }
        catch { case t: Throwable   => stageShutdown() }

      case Failure(Cmd.EOF)    => stageShutdown()
      case Failure(t)          =>
        stageShutdown()
        sendOutboundCommand(Cmd.Error(t))
    }
  }

  private def runRequest(buffer: ByteBuffer): Unit = {
    val h = HeaderCollection(headers.result())
    headers.clear()
    
    val connectionHeader = Header.Connection.from(h)

    // Do we expect a body?
    val body = mkBody(buffer)

    val req = Request(Method.resolve(this.method),
                      Uri.fromString(this.uri),
                      if (minor == 1) ServerProtocol.`HTTP/1.1` else ServerProtocol.`HTTP/1.0`,
                      h, body)

    route(req).runAsync {
      case \/-(resp) =>
        val rr = new StringWriter(512)
        rr ~ req.protocol.value.toString ~ ' ' ~ resp.status.code ~ ' ' ~ resp.status.reason ~ '\r' ~ '\n'

        var closeOnFinish = minor == 0

        resp.headers.foreach( header => rr ~ header.name.toString ~ ": " ~ header ~ '\r' ~ '\n' )
      
        // Should we add a keepalive header?
        connectionHeader.map{ h =>
          if (h.values.head.equalsIgnoreCase("Keep-Alive")) {
            logger.trace("Found Keep-Alive header")
            closeOnFinish = false
            rr ~ Header.Connection.name.toString ~ ':' ~ "Keep-Alive" ~ '\r' ~ '\n'
          } else if (h.values.head.equalsIgnoreCase("close")) closeOnFinish = true
          else sys.error("Unknown Connection header")
        }
      
        rr ~ '\r' ~ '\n'
      
        val b = ByteBuffer.wrap(rr.result().getBytes("US-ASCII"))

        // Write the headers and then start writing the body
        val bodyEncoder = {
          if (minor == 0) {
            val length = Header.`Content-Length`.from(resp.headers).map(_.length).getOrElse(-1)
            new StaticWriter(b, length, this)
          } else {
            Header.`Transfer-Encoding`.from(resp.headers).map{ h =>
              if (h.values.head != TransferCoding.chunked) sys.error("Unknown transfer encoding")
              new ChunkProcessWriter(b, this)
            }.orElse(Header.`Content-Length`.from(resp.headers).map{ c => new StaticWriter(b, c.length, this)})
              .getOrElse(new ChunkProcessWriter(b, this))
          }
        }

        bodyEncoder.writeProcess(resp.body).runAsync {
          case \/-(_) =>
            if (closeOnFinish) {
              closeConnection()
              logger.trace("Request/route requested closing connection.")
            }
            else {
              reset()
              requestLoop()
            }  // Serve another connection


          case -\/(t) => logger.error("Error writing body", t)
        }

      case -\/(t) =>
        logger.error("Error running route", t)
        closeConnection() // TODO: We need to deal with these errors properly
    }
  }

  private def closeConnection() {
    stageShutdown()
    sendOutboundCommand(Cmd.Shutdown)
  }


  private def mkBody(buffer: ByteBuffer): HttpBody = {
    if (contentComplete()) return HttpBody.empty

    var currentbuffer = buffer.asReadOnlyBuffer()

    // TODO: we need to work trailers into here somehow
    val t = Task.async[Chunk]{ cb =>
      if (!contentComplete()) {
        def go(): Future[BodyChunk] = {
          val result = parseContent(currentbuffer)
          if (result != null) { // we have a chunk
            Future.successful(BodyChunk.fromArray(result.array(), result.position, result.remaining))
          }
          else if (contentComplete()) Future.failed(End)
          else channelRead().flatMap{ b =>
            currentbuffer = b
            go()
          }
        }

        go().onComplete{
          case Success(b) => cb(\/-(b))
          case Failure(t) => cb(-\/(t))
        }(directec)

      } else { cb(-\/(End))}
    }

    val cleanup = Task.async[Unit](cb =>
      drainBody(currentbuffer).onComplete{
        case Success(_) => cb(\/-())
        case Failure(t) => cb(-\/(t))
    })

    await(t)(emit, cleanup = await(cleanup)(_ => halt))
  }

  private def drainBody(buffer: ByteBuffer): Future[Unit] = {
    if (!contentComplete()) {
      parseContent(buffer)
      channelRead().flatMap(drainBody)
    }
    else Future.successful()
  }

  override protected def stageShutdown(): Unit = {
    logger.info("Shutting down HttpPipeline")
    shutdownParser()
    super.stageShutdown()
  }

  def headerComplete(name: String, value: String) = {
    logger.trace(s"Received header '$name: $value'")
    headers += Header(name, value)
  }

  def submitRequestLine(methodString: String, uri: String, scheme: String, majorversion: Int, minorversion: Int) {
    logger.trace(s"Received request($methodString $uri $scheme/$majorversion.$minorversion)")
    this.uri = uri
    this.method = methodString
    this.major = majorversion
    this.minor = minorversion
  }
}
