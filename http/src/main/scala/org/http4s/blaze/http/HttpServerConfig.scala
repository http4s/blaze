package org.http4s.blaze.http

import org.http4s.blaze.util.Execution

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

/**
  * General configuration options for http servers
  *
  * TODO: what about things like listening address and port?
  */
case class HttpServerConfig(
  maxNonBodyBytes: Int = 16*1024,                           // Max bytes to accept as part of headers and request line
  requestPreludeTimeout: Duration = Duration.Inf,           // Timeout for the next request before considering the session lost
  serviceTimeout: Duration = Duration.Inf,                  // Timeout to apply to the service before sending a
  serviceExecutor: ExecutionContext = Execution.trampoline) // Executor to run the service future in.
