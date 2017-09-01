package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.http2.SettingsDecoder.SettingsFrame
import org.http4s.blaze.http.http2._
import org.http4s.blaze.pipeline.stages.OneMessageStage
import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage}
import org.http4s.blaze.util.{BufferTools, Execution, StageTools}

import scala.concurrent.Future
import scala.util.Failure

private[http] class Http2TlsServerHandshaker(
    mySettings: ImmutableHttp2Settings,
    nodeBuilder: Int => LeafBuilder[StreamMessage])
  extends PriorKnowledgeHandshaker[Unit](mySettings) {

  override protected def stageStartup(): Unit = synchronized {
    super.stageStartup()
    handshake().onComplete {
      case Failure(ex) =>
        logger.error(ex)("Failed to received prelude")
        sendOutboundCommand(Command.Disconnect)
      case _ => ()
    }
  }

  override protected def handshakeComplete(peerSettings: MutableHttp2Settings, data: ByteBuffer): Future[Unit] =
    Future(installHttp2ServerStage(peerSettings, data))

  override protected def handlePrelude(): Future[ByteBuffer] =
    StageTools.accumulateAtLeast(bits.clientHandshakeString.length, this).flatMap { buf =>
      val prelude = BufferTools.takeSlice(buf, bits.clientHandshakeString.length)
      val preludeString = StandardCharsets.UTF_8.decode(prelude).toString
      if (preludeString == bits.clientHandshakeString) Future.successful(buf)
      else {
        val msg = s"Invalid prelude: $preludeString"
        logger.error(msg)
        Future.failed(Http2Exception.PROTOCOL_ERROR.goaway(msg))
      }
    }

  // Setup the pipeline with a new Http2ClientStage and start it up, then return it.
  private def installHttp2ServerStage(peerSettings: MutableHttp2Settings, remainder: ByteBuffer): Unit = {
    logger.debug(s"Installing pipeline with settings: $peerSettings")
    val stage = Http2ServerConnectionImpl.default(mySettings, peerSettings, nodeBuilder)
    var newTail = LeafBuilder(stage)
    if (remainder.hasRemaining) {
      // We may have some extra data that we need to inject into the pipeline
      newTail = newTail.prepend(new OneMessageStage[ByteBuffer](remainder))
    }

    this.replaceTail(newTail, true)
    ()
  }
}
