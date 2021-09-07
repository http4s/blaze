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
package http2.server

import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import java.util
import org.http4s.blaze.internal.compat.CollectionConverters._
import org.http4s.blaze.pipeline.{Command => Cmd, LeafBuilder, TailStage}
import org.http4s.blaze.util.Execution.trampoline
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/** Dynamically inject an appropriate pipeline using ALPN negotiation.
  *
  * @param engine
  *   the `SSLEngine` in use for the connection
  * @param selector
  *   selects the preferred protocol from the sequence of supported clients. May receive an empty
  *   sequence.
  * @param builder
  *   builds the appropriate pipeline based on the negotiated protocol
  */
final class ALPNServerSelector(
    engine: SSLEngine,
    selector: Set[String] => String,
    builder: String => LeafBuilder[ByteBuffer]
) extends TailStage[ByteBuffer] {

  engine.setHandshakeApplicationProtocolSelector(
    new util.function.BiFunction[SSLEngine, util.List[String], String] {
      def apply(engine: SSLEngine, protocols: util.List[String]): String = {
        val available = protocols.asScala.toList
        logger.debug("Available protocols: " + available)
        selector(available.toSet)
      }
    })

  override def name: String = "PipelineSelector"

  override protected def stageStartup(): Unit =
    // This shouldn't complete until the handshake is done and ALPN has been run.
    channelWrite(Nil).onComplete {
      case Success(_) => selectPipeline()
      case Failure(Cmd.EOF) => // NOOP
      case Failure(t) =>
        logger.error(t)(s"$name failed to startup")
        closePipeline(Some(t))
    }(trampoline)

  private def selectPipeline(): Unit =
    try {
      val protocol = Option(engine.getApplicationProtocol()).getOrElse(selector(Set.empty))
      val b = builder(protocol)
      this.replaceTail(b, true)
      ()
    } catch {
      case NonFatal(t) =>
        logger.error(t)("Failure building pipeline")
        closePipeline(Some(t))
    }
}
