package org.http4s.blaze.http.endtoend.scaffolds

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.{Http2Settings, StreamMessage}
import org.http4s.blaze.http.http2.server.{Http2ServerStage, Http2TlsServerHandshaker}
import org.http4s.blaze.http.{HttpServerStageConfig, _}
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}

class Http2ServerScaffold(service: HttpService) extends ServerScaffold {

  private def http2Stage(service: HttpService, config: HttpServerStageConfig): TailStage[ByteBuffer] = {
    def newNode(streamId: Int): LeafBuilder[StreamMessage] = {
      LeafBuilder(new Http2ServerStage(streamId, service, config))
    }

    val mySettings = Http2Settings.default.copy(
      maxHeaderListSize = config.maxNonBodyBytes)

    new Http2TlsServerHandshaker(mySettings, newNode)
  }

  override protected def newLeafBuilder(): LeafBuilder[ByteBuffer] = {
    val config = HttpServerStageConfig()
    LeafBuilder(http2Stage(service, config))
  }
}