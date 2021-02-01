/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.http

import org.http4s.blaze.util.Execution

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

/** General configuration options for http servers
  *
  * TODO: what about things like listening address and port?
  */
case class HttpServerStageConfig(
    maxNonBodyBytes: Int = 16 * 1024, // Max bytes to accept as part of headers and request line
    requestPreludeTimeout: Duration =
      Duration.Inf, // Timeout for the next request before considering the session lost
    serviceTimeout: Duration = Duration.Inf, // Timeout to apply to the service before sending a
    maxConcurrentStreams: Int =
      100, // Maximum number of inbound streams we allow concurrently for HTTP2
    serviceExecutor: ExecutionContext =
      Execution.trampoline) // Executor to run the service future in.
