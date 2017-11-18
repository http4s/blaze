package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.http2._
import org.http4s.blaze.util.Execution

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

private[http2] class Http2MockTools(isClient: Boolean) extends SessionCore {

  def flowStrategy: FlowStrategy = new DefaultFlowStrategy(localSettings)

  override val localSettings: MutableHttp2Settings = MutableHttp2Settings.default()

  override val remoteSettings: MutableHttp2Settings = MutableHttp2Settings.default()

  lazy val headerEncoder: HeaderEncoder = new HeaderEncoder(remoteSettings.headerTableSize)

  lazy val headerDecoder: HeaderDecoder =
    new HeaderDecoder(localSettings.maxHeaderListSize,
      true, // discard overflow headers
      localSettings.headerTableSize)

  lazy val writeListener: MockWriteListener = new MockWriteListener

  lazy val idManager: StreamIdManager = StreamIdManager(isClient)

  override lazy val serialExecutor: ExecutionContext = Execution.trampoline

  override lazy val sessionFlowControl: MockFlowControl =
    new MockFlowControl(flowStrategy, localSettings, remoteSettings)

  override lazy val http2Encoder: Http2FrameEncoder =
    new Http2FrameEncoder(remoteSettings, headerEncoder)

  override lazy val http2Decoder: Http2FrameDecoder =
    new Http2FrameDecoder(localSettings, frameListener)

  def newStream(id: Int): MockHttp2StreamState = new MockHttp2StreamState(id, this)

  override def invokeShutdownWithError(ex: Option[Throwable], phase: String): Unit = ???

  override val writeController: WriteController = null
  override val pingManager: PingManager = null
  override val streamManager: StreamManager[StreamState] = null
}
