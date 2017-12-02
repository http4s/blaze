package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.http2.Http2Connection.ConnectionState
import org.http4s.blaze.http.http2._
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.util.Execution

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

private[http2] class MockTools(isClient: Boolean) extends SessionCore {

  def flowStrategy: FlowStrategy = new DefaultFlowStrategy(localSettings)

  lazy val frameListener: MockHeaderAggregatingFrameListener = new MockHeaderAggregatingFrameListener

  override val localSettings: MutableHttp2Settings = MutableHttp2Settings.default()

  override val remoteSettings: MutableHttp2Settings = MutableHttp2Settings.default()

  lazy val headerEncoder: HeaderEncoder = new HeaderEncoder(remoteSettings.headerTableSize)

  lazy val frameEncoder: Http2FrameEncoder = new Http2FrameEncoder(remoteSettings, headerEncoder)

  lazy val idManager: StreamIdManager = StreamIdManager(isClient)

  override lazy val serialExecutor: ExecutionContext = Execution.trampoline

  override lazy val sessionFlowControl: MockFlowControl = new MockFlowControl(this)

  override lazy val http2Encoder: Http2FrameEncoder =
    new Http2FrameEncoder(remoteSettings, headerEncoder)

  override lazy val http2Decoder: Http2FrameDecoder =
    new Http2FrameDecoder(localSettings, frameListener)

  override val writeController: MockWriteController = new MockWriteController

  override lazy val pingManager: PingManager = new PingManager(this)

  override def streamManager: StreamManager = ???

  // Behaviors
  override def newInboundStream(streamId: Int): Option[LeafBuilder[StreamMessage]] = ???

  override def state: ConnectionState = ???

  override def invokeDrain(gracePeriod: Duration): Unit = ???

  override def invokeGoaway(lastHandledStream: Int, message: String): Unit = ???

  override def invokeShutdownWithError(ex: Option[Throwable], phase: String): Unit = ???
}


