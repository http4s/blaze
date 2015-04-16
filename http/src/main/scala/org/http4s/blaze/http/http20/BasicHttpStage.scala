package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.util.Locale

import org.http4s.blaze.http._
import org.http4s.blaze.http.http20.NodeMsg.Http2Msg
import org.http4s.blaze.pipeline.{ Command => Cmd }
import org.http4s.blaze.pipeline.TailStage
import org.http4s.blaze.util.BufferTools
import Http2Exception.{ PROTOCOL_ERROR, INTERNAL_ERROR }

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.{Success, Failure}

class BasicHttpStage(streamId: Int,
                      maxBody: Long,
                      timeout: Duration,
                           ec: ExecutionContext,
                      service: HttpService) extends TailStage[Http2Msg] {

  import Http2StageTools._

  import NodeMsg.{ DataFrame, HeadersFrame }

  private implicit def _ec = ec   // for all the onComplete calls

  override def name = "BasicHTTPNode"

  override protected def stageStartup(): Unit = {
    super.stageStartup()
    readHeaders()
  }

  private def shutdownWithCommand(cmd: Cmd.OutboundCommand): Unit = {
    stageShutdown()
    sendOutboundCommand(cmd)
  }

  private def readHeaders(): Unit = {
    channelRead(timeout = timeout).onComplete  {
      case Success(HeadersFrame(_, endStream, hs)) =>
        if (endStream) checkAndRunRequest(hs, BufferTools.emptyBuffer)
        else getBody(hs, 0, new ArrayBuffer[ByteBuffer](16))

      case Success(frame) =>
        val e = PROTOCOL_ERROR(s"Received invalid frame: $frame", streamId, fatal = true)
        shutdownWithCommand(Cmd.Error(e))

      case Failure(Cmd.EOF) => shutdownWithCommand(Cmd.Disconnect)

      case Failure(t) =>
        logger.error(t)("Unknown error in readHeaders")
        val e = INTERNAL_ERROR(s"Unknown error", streamId, fatal = true)
        shutdownWithCommand(Cmd.Error(e))
    }
  }

  private def getBody(hs: Headers, bytesRead: Long, acc: ArrayBuffer[ByteBuffer]): Unit = channelRead(timeout = timeout).onComplete {
    case Success(DataFrame(last, bytes)) =>
      val totalBytes = bytesRead + bytes.remaining()
      if (maxBody > 0 && totalBytes > maxBody) {
        renderResponse(HttpResponse.EntityTooLarge())
      }
      else {
        if (bytes.hasRemaining()) acc += bytes
        if (!last) getBody(hs, totalBytes, acc)
        else checkAndRunRequest(hs, BufferTools.joinBuffers(acc))  // Finished with body
      }

    case Success(HeadersFrame(_, true, ts)) =>
      logger.info("Appending trailers: " + ts)
      checkAndRunRequest(hs ++ ts, BufferTools.joinBuffers(acc))

    case Success(other) =>  // This should cover it
      val msg = "Received invalid frame while accumulating body: " + other
      logger.info(msg)
      val e = PROTOCOL_ERROR(msg, fatal = true)
      shutdownWithCommand(Cmd.Error(e))

    case Failure(Cmd.EOF) =>
      logger.debug("EOF while accumulating body")
      shutdownWithCommand(Cmd.Disconnect)

    case Failure(t) =>
      logger.error(t)("Error in getBody(). Headers: " + hs)
      val e = INTERNAL_ERROR(streamId, fatal = true)
      shutdownWithCommand(Cmd.Error(e))
  }

  private def checkAndRunRequest(hs: Headers, body: ByteBuffer): Unit = {

    val normalHeaders = new ArrayBuffer[(String, String)](hs.size)
    var method: String = null
    var scheme: String = null
    var path: String = null
    var error: String = ""
    var pseudoDone = false

    hs.foreach {
      case (Method, v)    =>
        if (pseudoDone) error += "Pseudo header in invalid position. "
        else if (method == null) method = v
        else error += "Multiple ':method' headers defined. "

      case (Scheme, v)    =>
        if (pseudoDone) error += "Pseudo header in invalid position. "
        else if (scheme == null) scheme = v
        else error += "Multiple ':scheme' headers defined. "

      case (Path, v)      =>
        if (pseudoDone) error += "Pseudo header in invalid position. "
        else if (path == null)   path   = v
        else error += "Multiple ':path' headers defined. "

      case (Authority, _) => // NOOP; TODO: we should keep the authority header
        if (pseudoDone) error += "Pseudo header in invalid position. "

      case h@(k, _) if k.startsWith(":") => error += s"Invalid pseudo header: $h. "
      case h@(k, _) if !validHeaderName(k) => error += s"Invalid header key: $k. "

      case hs =>    // Non pseudo headers
        pseudoDone = true
        hs match {
          case h@(Connection, _) => error += s"HTTP/2.0 forbids connection specific headers: $h. "

          case (ContentLength, v) =>
            try {
              val sz = Integer.valueOf(v)
              if (sz != body.remaining())
                error += s"Invalid content-length, expected: ${body.remaining()}, header: $sz"
            }
            catch { case t: NumberFormatException => error += s"Invalid content-length: $v. " }

          case h@(TE, v) =>
            if (!v.equalsIgnoreCase("trailers")) error += s"HTTP/2.0 forbids TE header values other than 'trailers'. "
          // ignore otherwise

          case header => normalHeaders += header
      }
    }

    if (method == null || scheme == null || path == null) {
      error += s"Invalid request: missing pseudo headers. Method: $method, Scheme: $scheme, path: $path. "
    }

    if (error.length() > 0) shutdownWithCommand(Cmd.Error(PROTOCOL_ERROR(error, fatal = false)))
    else service(method, path, normalHeaders, body).onComplete {
      case Success(resp) => renderResponse(resp)
      case Failure(t) => shutdownWithCommand(Cmd.Error(t))
    }
  }

  private def renderResponse(resp: Response): Unit = resp match {
    case HttpResponse(code, _, headers, body) =>
                                                      // probably unnecessary micro-opt
      val hs = new ArrayBuffer[(String, String)](headers match {case b: IndexedSeq[_] => b.size + 1; case _ => 16 })
      hs += ((Status, Integer.toString(code)))
      headers.foreach{ case (k, v) => hs += ((k.toLowerCase(Locale.ROOT), v)) }

      val msgs = if (body.hasRemaining) HeadersFrame(None, false, hs)::DataFrame(true, body)::Nil
                 else                   HeadersFrame(None, true, hs)::Nil

      channelWrite(msgs, timeout).onComplete {
        case Success(_)       => shutdownWithCommand(Cmd.Disconnect)
        case Failure(Cmd.EOF) => stageShutdown()
        case Failure(t)       => shutdownWithCommand(Cmd.Error(t))
      }

    case other =>
      val msg = "Unsupported response type: " + other
      logger.error(msg)
      shutdownWithCommand(Cmd.Error(INTERNAL_ERROR(msg, fatal = false)))
  }
}

