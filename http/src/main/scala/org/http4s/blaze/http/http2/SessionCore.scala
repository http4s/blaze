package org.http4s.blaze.http.http2

import org.http4s.blaze.pipeline.LeafBuilder

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

/** The Http2 session has a lot of interconnected pieces and the SessionCore
  * provides a 'bag-o-references' so that each component can reference each
  * other. This helps to avoid construction order conflicts.
  */
private trait SessionCore {
  // Fields
  def serialExecutor: ExecutionContext

  def localSettings: Http2Settings // The settings of this side

  def remoteSettings: MutableHttp2Settings // The peer's settings.

  def sessionFlowControl: SessionFlowControl

  def http2Decoder: FrameDecoder

  def http2Encoder: FrameEncoder

  def writeController: WriteController

  def idManager: StreamIdManager

  def streamManager: StreamManager

  def pingManager: PingManager

  // Properties

  def state: Connection.State

  val isClient: Boolean

  // Behaviors
  def newInboundStream(streamId: Int): Option[LeafBuilder[StreamMessage]]

  /** Shutdown the session due to unhandled exception
    *
    * This is an emergency shutdown, and the session is in an undefined state.
    * @note this method must be idempotent (even for reentrant calls) as it
    *       may be recalled by streams during the close process, etc.
    */
  def invokeShutdownWithError(ex: Option[Throwable], phase: String): Unit

  /** Signal to the session to shutdown gracefully
    *
    * This will entail shutting down the [[StreamManager]] and waiting for
    * all write interests to drain.
    */
  def invokeGoAway(lastHandledOutboundStream: Int, error: Http2SessionException): Unit

  def invokeDrain(gracePeriod: Duration): Unit
}
