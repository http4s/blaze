package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2.Http2Connection.ConnectionState
import org.http4s.blaze.pipeline.LeafBuilder
import scala.concurrent.duration.Duration

private class MockTools(isClient: Boolean) extends SessionCore {

  override lazy val serialExecutor: DelayedExecutionContext = new DelayedExecutionContext

  override lazy val localSettings: Http2Settings = MutableHttp2Settings.default()
  override lazy val remoteSettings: MutableHttp2Settings = MutableHttp2Settings.default()

  lazy val headerEncoder: HeaderEncoder = new HeaderEncoder(remoteSettings.headerTableSize)

  lazy val headerDecoder: HeaderDecoder =
    new HeaderDecoder(localSettings.maxHeaderListSize,
      true, // discard overflow headers
      localSettings.headerTableSize)

  lazy val frameEncoder: Http2FrameEncoder = new Http2FrameEncoder(remoteSettings, headerEncoder)

  override lazy  val http2Encoder: Http2FrameEncoder = new Http2FrameEncoder(remoteSettings, headerEncoder)

  override val sessionFlowControl: SessionFlowControl = new MockFlowControl(this)

  override lazy val writeController: WriteController = new WriteController {
    override def write(data: Seq[ByteBuffer]): Unit = ???
    override def write(data: ByteBuffer): Unit = ???
  }

  override lazy val http2Decoder: Http2FrameDecoder = ???

  override lazy val pingManager: PingManager = ???

  override lazy val streamManager: StreamManager = ???


  override def state: ConnectionState = ???

  override def invokeShutdownWithError(ex: Option[Throwable], phase: String): Unit = ???

  override def newInboundStream(streamId: Int): Option[LeafBuilder[StreamMessage]] = ???

  override def invokeDrain(gracePeriod: Duration): Unit = ???

  override def invokeGoaway(lastHandledStream: Int, message: String): Unit = ???
}
