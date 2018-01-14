package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.http2._
import org.http4s.blaze.pipeline.stages.{BasicTail, OneMessageStage}
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.http4s.blaze.util.{BufferTools, Execution, StageTools}

import scala.concurrent.Future
import scala.util.Failure

// TODO: can we reduce the visibility of this?
private[http] class Http2TlsServerHandshaker(
                                              localSettings: ImmutableHttp2Settings,
                                              nodeBuilder: Int => Option[LeafBuilder[StreamMessage]])
  extends PriorKnowledgeHandshaker[Unit](localSettings) {

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
    StageTools.accumulateAtLeast(bits.ClientHandshakeString.length, this).flatMap { buf =>
      val prelude = BufferTools.takeSlice(buf, bits.ClientHandshakeString.length)
      val preludeString = StandardCharsets.UTF_8.decode(prelude).toString
      if (preludeString == bits.ClientHandshakeString) Future.successful(buf)
      else {
        val msg = s"Invalid prelude: $preludeString"
        logger.error(msg)
        Future.failed(Http2Exception.PROTOCOL_ERROR.goaway(msg))
      }
    }

  // Setup the pipeline with a new Http2ClientStage and start it up, then return it.
  private def installHttp2ServerStage(remoteSettings: MutableHttp2Settings, remainder: ByteBuffer): Unit = {
    logger.debug(s"Installing pipeline with settings: $remoteSettings")
    val tail = new BasicTail[ByteBuffer]("http2ServerTail")

    var newTail = LeafBuilder(tail)
    if (remainder.hasRemaining) {
      // We may have some extra data that we need to inject into the pipeline
      newTail = newTail.prepend(new OneMessageStage[ByteBuffer](remainder))
    }

    this.replaceTail(newTail, true)
    val flowStrategy = new DefaultFlowStrategy(localSettings)
    // TODO: The instance should start itself up, but who will have a reference to it?
    // TODO: Maybe through the continuations attached to the readloop?
    new Http2ConnectionImpl(
      isClient = false,
      tailStage = tail,
      localSettings = localSettings,
      remoteSettings = remoteSettings,  // peer settings
      flowStrategy = flowStrategy,
      inboundStreamBuilder = nodeBuilder,
      parentExecutor = Execution.trampoline)
  }
}
