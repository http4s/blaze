package org.http4s.blaze.http.endtoend.scaffolds

import java.nio.ByteBuffer

import org.http4s.blaze.http.{HttpServerStageConfig, HttpService}
import org.http4s.blaze.http.http1.server.Http1ServerStage
import org.http4s.blaze.pipeline.LeafBuilder

/** HTTP/1.x server implementation
  *
  * Basic HTTP/1.x, no SSL.
  */
class Http1ServerScaffold(service: HttpService) extends ServerScaffold {
  override protected def newLeafBuilder(): LeafBuilder[ByteBuffer] = {
    val config = HttpServerStageConfig() // just the default config, for now
    LeafBuilder(new Http1ServerStage(service, config))
  }
}
