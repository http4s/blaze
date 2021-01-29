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

package org.http4s.blaze.http.endtoend.scaffolds

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.{DefaultFlowStrategy, Http2Settings, StreamFrame}
import org.http4s.blaze.http.http2.server.{ServerPriorKnowledgeHandshaker, ServerStage}
import org.http4s.blaze.http.{HttpServerStageConfig, _}
import org.http4s.blaze.pipeline.{LeafBuilder, TailStage}

/** HTTP/2 server implementation
  *
  * Prior Knowledge HTTP/2 upgrade pathway
  */
class Http2ServerScaffold(service: HttpService) extends ServerScaffold {
  private def http2Stage(
      service: HttpService,
      config: HttpServerStageConfig): TailStage[ByteBuffer] = {
    def newNode(streamId: Int): LeafBuilder[StreamFrame] =
      LeafBuilder(new ServerStage(streamId, service, config))

    val localSettings = Http2Settings.default.copy(maxHeaderListSize = config.maxNonBodyBytes)

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
