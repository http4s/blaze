/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine

import org.http4s.blaze.http._
import org.http4s.blaze.http.ALPNTokens._
import org.http4s.blaze.http.http1.server.Http1ServerStage
import org.http4s.blaze.http.http2.{DefaultFlowStrategy, Http2Settings, StreamFrame}
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}
import org.log4s.getLogger

object ServerSelector {
  private val logger = getLogger

  def apply(
      engine: SSLEngine,
      service: HttpService,
      config: HttpServerStageConfig): ALPNServerSelector = {
    def builder(s: String): LeafBuilder[ByteBuffer] = s match {
      case H2 | H2_14 => LeafBuilder(http2Stage(service, config))
      case _ => LeafBuilder(http1xStage(service, config))
    }

    def selector(protocols: Set[String]): String =
      if (protocols(H2)) H2
      else if (protocols(H2_14)) H2_14
      else HTTP_1_1

    new ALPNServerSelector(engine, selector, builder)
  }

  private def http1xStage(
      service: HttpService,
      config: HttpServerStageConfig): TailStage[ByteBuffer] =
    new Http1ServerStage(service, config)

  private def http2Stage(
      service: HttpService,
      config: HttpServerStageConfig): TailStage[ByteBuffer] = {
    logger.debug("Selected HTTP2")

    def newNode(streamId: Int): LeafBuilder[StreamFrame] =
      LeafBuilder(new ServerStage(streamId, service, config))

    val localSettings =
      Http2Settings.default.copy(
        maxConcurrentStreams = config.maxConcurrentStreams,
        maxHeaderListSize = config.maxNonBodyBytes)

    new ServerPriorKnowledgeHandshaker(
      localSettings = localSettings,
      flowStrategy = new DefaultFlowStrategy(localSettings),
      nodeBuilder = newNode)
  }
}
