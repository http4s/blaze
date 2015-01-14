package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import java.util.Locale

import org.http4s.blaze.http._
import org.http4s.blaze.pipeline.{ Command => Cmd }
import org.http4s.blaze.pipeline.TailStage
import org.http4s.blaze.util.BufferTools
import Http2Exception.{ PROTOCOL_ERROR, INTERNAL_ERROR }

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.util.{Success, Failure}

class BasicHttpStage(timeout: Duration, ec: ExecutionContext, service: HttpService) extends TailStage[NodeMsg.Http2Msg[Headers]] {

  import BasicHttpStage._

  private type Http2Msg = NodeMsg.Http2Msg[Headers]
  private type Http2Hs  = NodeMsg.HeadersFrame[Headers]
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
        if (endStream) checkAndRunReqeust(hs, BufferTools.emptyBuffer)
        else getBody(hs, new ArrayBuffer[ByteBuffer](16))

      case Success(frame) =>
        val e = PROTOCOL_ERROR(s"Received invalid frame: $frame")
        shutdownWithCommand(Cmd.Error(e))

      case Failure(Cmd.EOF) => shutdownWithCommand(Cmd.Disconnect)

      case Failure(t) =>
        logger.error(t)("Unknown error in readHeaders")
        val e = INTERNAL_ERROR(s"Unknown error")
        shutdownWithCommand(Cmd.Error(e))
    }
  }

  private def getBody(hs: Headers, acc: ArrayBuffer[ByteBuffer]): Unit = channelRead(timeout = timeout).onComplete {
    case Success(DataFrame(last, bytes)) =>
      if (bytes.hasRemaining()) acc += bytes
      if (!last) getBody(hs, acc)
      else checkAndRunReqeust(hs, BufferTools.joinBuffers(acc))  // Finished with body

    case Success(HeadersFrame(_, true, ts)) =>
      logger.info("Appending trailers: " + ts)
      checkAndRunReqeust(hs ++ ts, BufferTools.joinBuffers(acc))

    case Success(other) =>
      val msg = "Received invalid frame while accumulating body: " + other
      logger.info(msg)
      val e = PROTOCOL_ERROR(msg)
      shutdownWithCommand(Cmd.Error(e))

    case Failure(Cmd.EOF) =>
      logger.debug("EOF while accumulating body")
      shutdownWithCommand(Cmd.Disconnect)

    case Failure(t) =>
      logger.error(t)("Error in getBody(). Headers: " + hs)
      val e = INTERNAL_ERROR()
      shutdownWithCommand(Cmd.Error(e))
  }

  private def checkAndRunReqeust(hs: Headers, body: ByteBuffer): Unit = {

    val normalHeaders = new ArrayBuffer[(String, String)](hs.size)
    var method: String = null
    var scheme: String = null
    var path: String = null
    var error: String = ""

    hs.foreach {
      case (Method, v)    => if (method == null) method = v else error += "Multiple ':method' headers defined. "
      case (Scheme, v)    => if (scheme == null) scheme = v else error += "Multiple ':scheme' headers defined. "
      case (Path, v)      => if (path == null)   path   = v else error += "Multiple ':path' headers defined. "
      case (Authority, _) => // NOOP; TODO: we should keep the authority header
      case h@(k, _) if k.startsWith(":") => logger.info(s"Unknown pseudo-header: $h")
      case header         => normalHeaders += header
    }

    if (method == null || scheme == null || path == null) {
      error += s"Invalid request: missing pseudo headers. Method: $method, Scheme: $scheme, path: $path. "
    }

    if (error.length() > 0) shutdownWithCommand(Cmd.Error(PROTOCOL_ERROR(error)))
    else service(method, path, normalHeaders, body).onComplete {
      case Success(resp) => renderResponse(resp)
      case Failure(t) => shutdownWithCommand(Cmd.Error(t))
    }
  }

  private def renderResponse(resp: Response): Unit = resp match {
    case SimpleHttpResponse(_, code, headers, body) =>
                                                      // probably unnecessary micro-opt
      val hs = new ArrayBuffer[(String, String)](headers match {case b: IndexedSeq[_] => b.size + 1; case _ => 16 })
      hs += ((Status, code.toString))
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
      shutdownWithCommand(Cmd.Error(INTERNAL_ERROR(msg)))
  }
}

private object BasicHttpStage {
  // Request pseudo headers
  val Method = ":method"
  val Scheme = ":scheme"
  val Path   = ":path"
  val Authority = ":authority"

  // Response pseudo header
  val Status = ":status"
}
