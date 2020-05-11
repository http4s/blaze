/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http1.server

import java.nio.ByteBuffer

import org.http4s.blaze.http.util.ServiceTimeoutFilter
import org.http4s.blaze.http.{HttpRequest, HttpServerStageConfig, RouteAction, _}
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.pipeline.TailStage
import org.http4s.blaze.util.Execution
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success}

/** Http1 Server tail stage
  *
  * `TailStage` capable of decoding requests, obtaining results from
  * the [[HttpService]], rendering the response and, if possible,
  * restarting to continue serving requests on the this connection.
  *
  * Keep-Alive is supported and managed automatically by this stage via the `Http1ServerCodec`
  * which examines the http version, and `connection`, `content-length`, and `transfer-encoding`
  * headers to determine if the connection can be reused for a subsequent dispatch.
  */
class Http1ServerStage(service: HttpService, config: HttpServerStageConfig)
    extends TailStage[ByteBuffer] {
  import Http1ServerStage._

  private implicit def implicitEC = Execution.trampoline
  val name = "HTTP/1.1_Stage"

  // The codec is responsible for reading data from `this` TailStage.
  private[this] val codec = new Http1ServerCodec(config.maxNonBodyBytes, this)

  /////////////////////////////////////////////////////////////////////////////////////////

  // On startup we begin the dispatch loop
  override def stageStartup(): Unit = {
    logger.debug("Starting HttpStage")
    dispatchLoop()
  }

  // wrapped service that includes whether the request requires the connection be closed and deals with any timeouts
  private val checkCloseService = {
    // Apply any service level timeout
    val timeoutService = ServiceTimeoutFilter(config.serviceTimeout)(service)

    req: HttpRequest =>
      timeoutService(req).map(resp => resp -> requestRequiresClose(req))(Execution.directec)
  }

  private def dispatchLoop(): Unit =
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
    Execution
      .withTimeout(config.requestPreludeTimeout, TryRequestTimeoutExec)(codec.getRequest())
      .flatMap(checkCloseService)(config.serviceExecutor)
      .recover {
        case RequestTimeoutException => newRequestTimeoutResponse()
      } // handle request timeouts
      .onComplete {
        case Success((resp, requireClose)) =>
          codec.renderResponse(resp, requireClose).onComplete {
            case Success(Http1ServerCodec.Reload) =>
              dispatchLoop() // continue the loop
            case Success(Http1ServerCodec.Close) =>
              closePipeline(None) // not able to server another on this session

            case Failure(EOF) =>
              logger.debug(EOF)("Failed to render response")
              closePipeline(None)

            case Failure(ex) =>
              logger.error(ex)("Failed to render response")
              closePipeline(Some(ex))
          }

        case Failure(EOF) =>
          closePipeline(None)

        case Failure(ex) =>
          logger.error(ex)("Failed to service request. Sending 500 response.")
          codec
            .renderResponse(RouteAction.InternalServerError(), forceClose = true)
            .onComplete(_ => closePipeline(None))
      }

  // Determine if this request requires the connection be closed
  private def requestRequiresClose(request: HttpRequest): Boolean = {
    val connHeader = request.headers.find {
      case (k, _) => k.equalsIgnoreCase(HeaderNames.Connection)
    }
    if (request.minorVersion == 0)
      connHeader match {
        case Some((_, v)) => !v.equalsIgnoreCase("keep-alive")
        case None => true
      }
    else
      connHeader match {
        case Some((_, v)) => v.equalsIgnoreCase("close")
        case None => false
      }
  }

  // TODO: can this be shared with http2?
  private def newRequestTimeoutResponse(): (RouteAction, Boolean) = {
    val msg = s"Request timed out after ${config.requestPreludeTimeout}"
    RouteAction.String(msg, 408, "Request Time-out", Nil) -> true // force close
  }

  override protected def stageShutdown(): Unit = {
    logger.info("Shutting down HttpPipeline")
    codec.shutdown()
  }
}

private object Http1ServerStage {
  object RequestTimeoutException extends Exception with NoStackTrace
  val TryRequestTimeoutExec = Failure(RequestTimeoutException)
}
