package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.Http2Connection.ConnectionState
import org.http4s.blaze.pipeline.LeafBuilder

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

/** The Http2 session has a lot of interconnected pieces and the SessionCore
  * provides a 'bag-o-references' so that each component can reference each
  * other. This helps to avoid construction order conflicts.
  */
private trait SessionCore {
  // Fields

  val serialExecutor: ExecutionContext

  val localSettings: Http2Settings // The settings of this side

  val remoteSettings: MutableHttp2Settings // The peers settings. These can change during the session.

  val sessionFlowControl: SessionFlowControl

  val http2Decoder: Http2FrameDecoder

  val http2Encoder: Http2FrameEncoder

  val writeController: WriteController

  val streamManager: StreamManager

  val pingManager: PingManager

  // Behaviors

  def newInboundStream(streamId: Int): Option[LeafBuilder[StreamMessage]]

  def invokeShutdownWithError(ex: Option[Throwable], phase: String): Unit

  def invokeGoaway(lastHandledStream: Int, message: String): Unit

  def invokeDrain(gracePeriod: Duration): Unit

  def state: ConnectionState
}
