package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.http2._
import org.http4s.blaze.pipeline.stages.{BasicTail, OneMessageStage}
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import org.http4s.blaze.util.{BufferTools, StageTools}

import scala.concurrent.Future
import scala.util.Failure

// TODO: can we reduce the visibility of this?
private[http] class Http2TlsServerHandshaker(
    mySettings: ImmutableHttp2Settings,
    nodeBuilder: Int => Option[LeafBuilder[StreamMessage]])
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
  private def installHttp2ServerStage(peerSettings: MutableHttp2Settings, remainder: ByteBuffer): Unit = {
    logger.debug(s"Installing pipeline with settings: $peerSettings")
    val tail = new BasicTail[ByteBuffer]("http2ServerTail")

    val stage = Http2ServerConnectionImpl.default(tail, mySettings, peerSettings, nodeBuilder)
    var newTail = LeafBuilder(tail)
    if (remainder.hasRemaining) {
      // We may have some extra data that we need to inject into the pipeline
      newTail = newTail.prepend(new OneMessageStage[ByteBuffer](remainder))
    }

    this.replaceTail(newTail, true)
    ()
  }
}
