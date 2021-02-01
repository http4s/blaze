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
import java.util
import javax.net.ssl.SSLEngine
import org.eclipse.jetty.alpn.ALPN
import org.http4s.blaze.internal.compat.CollectionConverters._
import org.http4s.blaze.pipeline.{Command => Cmd, LeafBuilder, TailStage}
import org.http4s.blaze.util.Execution._
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

class ALPNClientSelector(
    engine: SSLEngine,
    available: Seq[String],
    default: String,
    builder: String => LeafBuilder[ByteBuffer])
    extends TailStage[ByteBuffer] {
  require(available.nonEmpty)

  ALPN.put(engine, new ClientProvider)

  @volatile
  private var selected: Option[String] = None

  override def name: String = "Http2ClientALPNSelector"

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
      logger.debug(s"Client ALPN selected: $selected")
      val tail = builder(selected.getOrElse(default))
      this.replaceTail(tail, true)
      ()
    } catch {
      case NonFatal(t) =>
        logger.error(t)("Failure building pipeline")
        closePipeline(Some(t))
    }

  private class ClientProvider extends ALPN.ClientProvider {
    override val protocols: util.List[String] = available.asJava

    override def unsupported(): Unit = {
      ALPN.remove(engine)
      logger.info("ALPN client negotiation failed. Defaulting to head of seq")
    }

    override def selected(protocol: String): Unit = {
      ALPN.remove(engine)
      ALPNClientSelector.this.selected = Some(protocol)
    }
  }
}
