package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage}
import org.http4s.blaze.util.{Execution, SerialExecutionContext}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.Duration

private final class SessionCoreImpl(
  val isClient: Boolean,
  tailStage: TailStage[ByteBuffer],
  val localSettings: Http2Settings,
  val remoteSettings: MutableHttp2Settings,
  flowStrategy: FlowStrategy,
  inboundStreamBuilder: Int => Option[LeafBuilder[StreamMessage]],
  parentExecutor: ExecutionContext
) extends SessionCore {

  final protected val logger = org.log4s.getLogger

  @volatile
  private[this] var currentState: Connection.State = Connection.Running
  // used to signal that the session is closed
  private[this] val closedPromise = Promise[Unit]

  def onClose: Future[Unit] = closedPromise.future

  override val serialExecutor = new SerialExecutionContext(parentExecutor) {
    override def reportFailure(cause: Throwable): Unit =
      invokeShutdownWithError(Some(cause), "SerialExecutor")
  }

  private[this] val headerDecoder = new HeaderDecoder(
    maxHeaderListSize = localSettings.maxHeaderListSize,
    discardOverflowHeaders = true,
    maxTableSize = localSettings.headerTableSize)

  private[this] val headerEncoder: HeaderEncoder = new HeaderEncoder(remoteSettings.maxHeaderListSize)
  private[this] val frameListener = new SessionFrameListener(this, headerDecoder)

  override val http2Decoder = new FrameDecoder(localSettings, frameListener)
  override val http2Encoder = new FrameEncoder(remoteSettings, headerEncoder)
  override val writeController = new WriteControllerImpl(this, 64*1024, tailStage)
  override val pingManager: PingManager = new PingManager(this)
  override val sessionFlowControl: SessionFlowControl = new SessionFlowControlImpl(this, flowStrategy)
  override val streamManager: StreamManager = new StreamManagerImpl(this)
  override val idManager: StreamIdManager = StreamIdManager(isClient)

  override def state: Connection.State = currentState

  override def newInboundStream(streamId: Int): Option[LeafBuilder[StreamMessage]] = {
    // TODO: check the state
    inboundStreamBuilder(streamId)
  }

  // Must be called from within the session executor.
  // If an error is provided, a GO_AWAY is written and we wait for the writeController to
  // close the connection. If not, we do it.
  def invokeShutdownWithError(ex: Option[Throwable], phase: String): Unit = {
    if (state != Connection.Closed) {
      currentState = Connection.Closed

      ex match {
        case None | Some(EOF) =>
          streamManager.forceClose(None)
          closedPromise.trySuccess(())

        case Some(ex) =>
          val http2SessionError = ex match {
            case ex: Http2Exception =>
              logger.debug(ex)(s"Shutting down with HTTP/2 session in phase $phase")
              ex

            case other =>
              logger.warn(other)(s"Shutting down HTTP/2 with unhandled exception in phase $phase")
              Http2Exception.INTERNAL_ERROR.goaway("Unhandled internal exception")
          }

          streamManager.forceClose(Some(ex)) // Fail hard
        val goawayFrame = FrameSerializer.mkGoAwayFrame(
            idManager.lastInboundStream, http2SessionError)
          // TODO: maybe we should clear the `WriteController` before writing?
          writeController.write(goawayFrame)
          writeController.close().onComplete { _ =>
            tailStage.sendOutboundCommand(Command.Disconnect)
          }(serialExecutor)
      }
    }
  }

  // TODO: this is geared toward the server, what about the client?
  override def invokeDrain(gracePeriod: Duration): Unit = {
    if (currentState == Connection.Running) {
      // Don't set the state to draining because we'll do that in `invokeGoaway`

      // Start draining: send a GOAWAY and set a timer to shutdown
      val noError = Http2Exception.NO_ERROR.goaway(s"Session draining for duration $gracePeriod")
      val someNoError = Some(noError)
      val frame = FrameSerializer.mkGoAwayFrame(idManager.lastInboundStream, noError)
      writeController.write(frame)

      // Drain the StreamManager
      val lastHandledStream = idManager.lastOutboundStream
      invokeGoAway(lastHandledStream, noError)

      // Now set a timer to force closed the session after the expiration
      // if draining takes too long.
      val work = new Runnable {
        def run(): Unit = invokeShutdownWithError(someNoError, s"drainSession($gracePeriod)")
      }
      Execution.scheduler.schedule(work, serialExecutor, gracePeriod)
    }
  }

  // TODO: should we switch the dependency between drain and GOAWAY?
  override def invokeGoAway(lastHandledOutboundStream: Int, error: Http2SessionException): Unit = {
    if (currentState == Connection.Running) {
      currentState = Connection.Draining
      // Drain the `StreamManager` and then the `WriteController`, then close up.
      streamManager.goAway(lastHandledOutboundStream, error)
        .flatMap { _ => writeController.close() }(serialExecutor)
        .onComplete { _ => invokeShutdownWithError(None, "invokeGoaway") }(serialExecutor)
    }
  }
}
