package org.http4s.blaze.http


import java.nio.ByteBuffer

import org.http4s.blaze.http.util.{HeaderNames, ServiceTimeoutFilter}
import org.http4s.blaze.pipeline.{Command => Cmd, _}
import org.http4s.blaze.util.Execution
import org.http4s.blaze.pipeline.Command.EOF

import scala.util.{Failure, Success}
import scala.util.control.NoStackTrace

class HttpServerStage(service: HttpService, config: HttpServerConfig) extends TailStage[ByteBuffer] {
  import HttpServerStage._

  private implicit def implicitEC = Execution.trampoline
  val name = "HTTP/1.1_Stage"

  // The codec is responsible for reading data from `this` TailStage.
  private[this] val codec = new  HttpServerCodec(config.maxNonBodyBytes, this)

  /////////////////////////////////////////////////////////////////////////////////////////

  // Will act as our loop
  override def stageStartup() {
    logger.debug("Starting HttpStage")
    dispatchLoop()
  }

  // wrapped service that includes whether the request requires the connection be closed and deals with any timeouts
  private val checkCloseService = {
    // Apply any service level timeout
    val timeoutService = ServiceTimeoutFilter(config.serviceTimeout)(service)

    req: HttpRequest => timeoutService(req).map { resp =>
      resp -> requestRequiresClose(req)
    }(Execution.directec)
  }

  private def dispatchLoop(): Unit = {
     /* TODO: how can we do smart timeouts? What are the situations where one would want to do timeouts?
      * - Waiting for the request prelude
      *   - Probably needs to be done in the dispatch loop
      * - Waiting for the service
      *   - Can be a service middleware that races the service with a timeout
      * - Waiting for an entire body
      *   - Maybe this could be attached to the readers and writers
      * - Long durations of network silence when reading or rendering a body
      *   - These could probably be done by wrapping readers and writers
      */
    Execution.withTimeout(config.requestPreludeTimeout, TryRequestTimeoutExec)(codec.getRequest())
      .flatMap(checkCloseService)(config.serviceExecutor)
      .recover { case RequestTimeoutException => newRequestTimeoutResponse() }  // handle request timeouts
      .onComplete {
        case Success((resp, requireClose)) =>
          codec.renderResponse(resp, requireClose).onComplete {
            case Success(HttpServerCodec.Reload) => dispatchLoop()  // continue the loop
            case Success(HttpServerCodec.Close) => sendOutboundCommand(Cmd.Disconnect) // not able to server another on this session

            case Failure(EOF) => /* NOOP socket should now be closed */
            case Failure(ex) =>
              logger.error(ex)("Failed to render response")
              shutdownWithCommand(Cmd.Error(ex))
          }

        case Failure(EOF) => /* NOOP */
        case Failure(ex) =>
          val resp = make5xx(ex)
          codec.renderResponse(resp, forceClose = true).onComplete { _ =>
            shutdownWithCommand(Cmd.Error(ex))
          }
      }
  }

  private def make5xx(error: Throwable): RouteAction = {
    logger.error(error)("Failed to service request. Sending 500 response.")
    RouteAction.String(500, "Internal Server Error", Nil, "Internal Server Error.")
  }

  private def shutdownWithCommand(cmd: Cmd.OutboundCommand): Unit = {
    stageShutdown()
    sendOutboundCommand(cmd)
  }

  // Determine if this request requires the connection be closed
  private def requestRequiresClose(request: HttpRequest): Boolean = {
    val connHeader = request.headers.find { case (k, _) => k.equalsIgnoreCase(HeaderNames.Connection) }
    if (request.minorVersion == 0) connHeader match {
      case Some((_,v)) => !v.equalsIgnoreCase("keep-alive")
      case None => true
    } else connHeader match {
      case Some((_, v)) => v.equalsIgnoreCase("close")
      case None => false
    }
  }

  // TODO: can this be shared with http2?
  private def newRequestTimeoutResponse(): (RouteAction, Boolean) = {
    val msg = s"Request timed out after ${config.requestPreludeTimeout}"
    RouteAction.String(408, "Request Time-out", Nil, msg) -> true // force close
  }

  override protected def stageShutdown(): Unit = {
    logger.info("Shutting down HttpPipeline")
    codec.shutdown()
  }
}

private object HttpServerStage {
  object RequestTimeoutException extends Exception with NoStackTrace
  val TryRequestTimeoutExec = Failure(RequestTimeoutException)
}