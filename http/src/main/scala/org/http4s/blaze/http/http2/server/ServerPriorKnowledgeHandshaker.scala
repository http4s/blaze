/*
 * Copyright 2014-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import org.http4s.blaze.http.http2._
import org.http4s.blaze.pipeline.stages.{BasicTail, OneMessageStage}
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.util.{BufferTools, Execution, StageTools}
import scala.concurrent.Future
import scala.util.Failure

final class ServerPriorKnowledgeHandshaker(
    localSettings: ImmutableHttp2Settings,
    flowStrategy: FlowStrategy,
    nodeBuilder: Int => LeafBuilder[StreamFrame])
    extends PriorKnowledgeHandshaker[Unit](localSettings) {
  override protected def stageStartup(): Unit = synchronized {
    super.stageStartup()
    handshake().onComplete {
      case Failure(ex) =>
        logger.error(ex)("Failed to received prelude")
        closePipeline(None)
      case _ => ()
    }
  }

  override protected def handshakeComplete(
      remoteSettings: MutableHttp2Settings,
      data: ByteBuffer): Future[Unit] =
    Future(installHttp2ServerStage(remoteSettings, data))

  override protected def handlePreface(): Future[ByteBuffer] =
    StageTools.accumulateAtLeast(bits.PrefaceString.length, this).flatMap { buf =>
      val prelude = BufferTools.takeSlice(buf, bits.PrefaceString.length)

      if (prelude == bits.getPrefaceBuffer()) Future.successful(buf)
      else {
        val preludeString = StandardCharsets.UTF_8.decode(prelude).toString
        val msg = s"Invalid prelude: $preludeString"
        val ex = Http2Exception.PROTOCOL_ERROR.goaway(msg)
        logger.error(ex)(msg)
        Future.failed(ex)
      }
    }

  // Setup the pipeline with a new Http2ClientStage and start it up, then return it.
  private def installHttp2ServerStage(
      remoteSettings: MutableHttp2Settings,
      remainder: ByteBuffer): Unit = {
    logger.debug(s"Installing pipeline with settings: $remoteSettings")
    val tail = new BasicTail[ByteBuffer]("http2ServerTail")

    var newTail = LeafBuilder(tail)
    if (remainder.hasRemaining) {
      // We may have some extra data that we need to inject into the pipeline
      newTail = newTail.prepend(new OneMessageStage[ByteBuffer](remainder))
    }

    this.replaceTail(newTail, true)

    // The session starts itself up and drives the pipeline
    new ConnectionImpl(
      tailStage = tail,
      localSettings = localSettings,
      remoteSettings = remoteSettings,
      flowStrategy = flowStrategy,
      inboundStreamBuilder = Some(nodeBuilder),
      parentExecutor = Execution.trampoline
    )
    ()
  }
}
