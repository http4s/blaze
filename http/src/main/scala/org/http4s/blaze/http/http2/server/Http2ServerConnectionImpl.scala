package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2._
import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage}
import org.http4s.blaze.util.Execution

import scala.concurrent.{ExecutionContext, Future}

private object Http2ServerConnectionImpl {

  /** Construct a [[Http2ServerConnectionImpl]] with default settings */
  def default(
    mySettings: ImmutableHttp2Settings,
    peerSettings: MutableHttp2Settings,
    nodeBuilder: Int => LeafBuilder[StreamMessage]): Http2ServerConnectionImpl = {

    val headerEncoder = new HeaderEncoder(peerSettings.headerTableSize)
    val http2Encoder = new Http2FrameEncoder(peerSettings, headerEncoder)
    val headerDecoder = new HeaderDecoder(mySettings.maxHeaderListSize, mySettings.headerTableSize)
    val flowStrategy = new DefaultFlowStrategy(mySettings)
    val ec = Execution.trampoline

    new Http2ServerConnectionImpl(
      nodeBuilder,
      mySettings,
      peerSettings,
      http2Encoder,
      headerDecoder,
      flowStrategy,
      ec
    )
  }
}

private final class Http2ServerConnectionImpl(
    nodeBuilder: Int => LeafBuilder[StreamMessage],
    mySettings: ImmutableHttp2Settings, // the settings of this side
    peerSettings: MutableHttp2Settings,
    http2Encoder: Http2FrameEncoder,
    headerDecoder: HeaderDecoder,
    flowStrategy: FlowStrategy,
    executor: ExecutionContext)
  extends Http2ConnectionImpl(
    false, //isClient: Boolean,
    mySettings,
    peerSettings,  // peer settings
    http2Encoder: Http2FrameEncoder,
    headerDecoder,
    flowStrategy: FlowStrategy,
    executor: ExecutionContext
  ) with TailStage[ByteBuffer] {

  override def name: String = "ServerSessionImpl"

  // Boom. It begins.
  override protected def stageStartup(): Unit = {
    super.stageStartup()
    startSession()
  }

  override protected def sessionTerminated(): Unit =
    sendOutboundCommand(Command.Disconnect)

  // TODO: What about peer information?
  override protected def newInboundStream(streamId: Int): Option[LeafBuilder[StreamMessage]] =
    Some(nodeBuilder(streamId))

  // Need to be able to write data to the pipeline
  override protected def writeBytes(data: Seq[ByteBuffer]): Future[Unit] = {
    logger.debug(s"Writing ${data.length} buffers")
    channelWrite(data)
  }

  // Need to be able to read data
  override protected def readData(): Future[ByteBuffer] = channelRead()

  // Create a new decoder wrapping this Http2FrameHandler
  override protected def newHttp2Decoder(handler: Http2FrameHandler): Http2FrameDecoder =
    new Http2FrameDecoder(mySettings, handler)
}
