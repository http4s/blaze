package org.http4s.blaze.http.endtoend.scaffolds

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.{DefaultFlowStrategy, Http2Settings, StreamFrame}
import org.http4s.blaze.http.http2.server.{ServerStage, ServerPriorKnowledgeHandshaker}
import org.http4s.blaze.http.{HttpServerStageConfig, _}
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}

/** HTTP/2 server implementation
  *
  * Prior Knowledge HTTP/2 upgrade pathway
  */
class Http2ServerScaffold(service: HttpService) extends ServerScaffold {

  private def http2Stage(service: HttpService, config: HttpServerStageConfig): TailStage[ByteBuffer] = {
    def newNode(streamId: Int): LeafBuilder[StreamFrame] =
      LeafBuilder(new ServerStage(streamId, service, config))

    val localSettings = Http2Settings.default.copy(
      maxHeaderListSize = config.maxNonBodyBytes)

    new ServerPriorKnowledgeHandshaker(
      localSettings,
      new DefaultFlowStrategy(localSettings),
      newNode)
  }

  override protected def newLeafBuilder(): LeafBuilder[ByteBuffer] = {
    val config = HttpServerStageConfig()
    LeafBuilder(http2Stage(service, config))
  }
}