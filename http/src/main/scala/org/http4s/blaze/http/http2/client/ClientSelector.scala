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

package org.http4s.blaze.http.http2.client

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine

import org.http4s.blaze.http.http1.client.Http1ClientStage
import org.http4s.blaze.http.http2.{DefaultFlowStrategy, Http2Settings, ImmutableHttp2Settings}
import org.http4s.blaze.http.{ALPNTokens, HttpClientConfig, HttpClientSession}
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.util.Execution
import org.log4s.getLogger

import scala.concurrent.Promise

private[http] class ClientSelector(config: HttpClientConfig) {
  import ALPNTokens._

  private[this] val logger = getLogger

  def newStage(engine: SSLEngine, p: Promise[HttpClientSession]): ALPNClientSelector =
    new ALPNClientSelector(engine, AllTokens, HTTP_1_1, buildPipeline(p))

  private[this] val localSettings: ImmutableHttp2Settings =
    Http2Settings.default.copy(
      maxHeaderListSize =
        config.maxResponseLineLength + config.maxHeadersLength // the request line is part of the headers
    )

  private[this] def buildPipeline(p: Promise[HttpClientSession])(
      s: String): LeafBuilder[ByteBuffer] =
    s match {
      case H2 | H2_14 =>
        logger.debug(s"Selected $s, resulted in H2 protocol.")
        val f = new DefaultFlowStrategy(localSettings)
        val handshaker = new ClientPriorKnowledgeHandshaker(localSettings, f, Execution.trampoline)
        p.completeWith(handshaker.clientSession)
        LeafBuilder(handshaker)

      case _ =>
        logger.debug(s"Selected $s, resulted in HTTP1 protocol.")
        val clientStage = new Http1ClientStage(config)
        p.success(clientStage)
        LeafBuilder(clientStage)
    }
}
