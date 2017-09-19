package org.http4s.blaze.http.http2.client

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2._
import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.http.http2.SettingsDecoder.SettingsFrame
import org.http4s.blaze.pipeline.stages.OneMessageStage
import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage}
import org.http4s.blaze.util.{BufferTools, Execution}

import scala.concurrent.{ExecutionContext, Future, Promise}

/** Stage capable of performing the client HTTP2 handshake
  * and returning a `ClientSession` which is ready to dispatch
  * requests.
  *
  * The handshake goes like this:
  * - Send the handshake preface
  * - Send the initial settings frame
  * - Receive the initial settings frame
  * - Ack the initial settings frame
  * - Finish constructing the connection
  *
  * @param mySettings settings to transmit to the server while performing the handshake
  */
private[http] class Http2TlsClientHandshaker(
    mySettings: ImmutableHttp2Settings,
    flowStrategy: FlowStrategy,
    executor: ExecutionContext)
  extends PriorKnowledgeHandshaker[Http2ClientConnection](mySettings) {

  private[this] val session = Promise[Http2ClientConnection]

  def clientSession: Future[Http2ClientConnection] = session.future

  override protected def stageStartup(): Unit = {
    logger.debug("initiating handshake")
    session.tryCompleteWith(handshake())
    ()
  }

  override protected def handlePrelude(): Future[ByteBuffer] =
    channelWrite(bits.getHandshakeBuffer()).map { _ => BufferTools.emptyBuffer }

  override protected def handshakeComplete(
      peerSettings: MutableHttp2Settings,
      data: ByteBuffer): Future[Http2ClientConnection] = {
    val http2Encoder = new Http2FrameEncoder(peerSettings, new HeaderEncoder(peerSettings.headerTableSize))
    val h2ClientStage =
      new Http2ClientConnectionImpl(
        mySettings,
        peerSettings,
        http2Encoder,
        new HeaderDecoder(
          mySettings.maxHeaderListSize,
          /*discard overflow headers*/ true,
          mySettings.headerTableSize),
        flowStrategy,
        executor
      )

    var newTail = LeafBuilder(h2ClientStage)

    if (data.hasRemaining) {
      newTail = newTail.prepend(new OneMessageStage[ByteBuffer](data))
    }

    this.replaceTail(newTail, true)

    Future.successful(h2ClientStage)
  }
}

private object Http2TlsClientHandshaker {

  val DefaultClientSettings: Seq[Setting] = Vector(
    Http2Settings.ENABLE_PUSH(0) /*false*/
  )
}
