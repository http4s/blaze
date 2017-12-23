package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import org.http4s.blaze.http.http2.Http2Connection._
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.pipeline.{Command, LeafBuilder, TailStage}
import org.http4s.blaze.util.{BufferTools, Execution, SerialExecutionContext}
import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/** Representation of the http2 session. */
private abstract class Http2ConnectionImpl(
    isClient: Boolean,
    tailStage: TailStage[ByteBuffer],
    localSettings: Http2Settings, // The settings of this side
    remoteSettings: MutableHttp2Settings, // The peers settings. These can change during the session.
    flowStrategy: FlowStrategy,
    inboundStreamBuilder: Int => Option[LeafBuilder[StreamMessage]],
    parentExecutor: ExecutionContext)
  extends Http2Connection {

  final protected val logger = org.log4s.getLogger
  // used to signal that the session is closed
  private[this] val closedPromise = Promise[Unit]

  @volatile
  private[this] var currentState: ConnectionState = Running
  private[this] var started = false

  protected object Core extends SessionCore {
    override val localSettings: Http2Settings = Http2ConnectionImpl.this.localSettings
    override val remoteSettings: MutableHttp2Settings = Http2ConnectionImpl.this.remoteSettings

    override val serialExecutor = new SerialExecutionContext(parentExecutor) {
      override def reportFailure(cause: Throwable): Unit =
        invokeShutdownWithError(Some(cause), "SerialExecutor")
    }

    private[this] val headerDecoder = new HeaderDecoder(
      maxHeaderListSize = localSettings.maxHeaderListSize,
      discardOverflowHeaders = true,
      maxTableSize = localSettings.headerTableSize
    )

    private[this] val headerEncoder: HeaderEncoder = new HeaderEncoder(remoteSettings.maxHeaderListSize)
    private[this] val frameListener = new SessionFrameListener(this, headerDecoder)

    override val http2Decoder = new Http2FrameDecoder(localSettings, frameListener)
    override val http2Encoder = new Http2FrameEncoder(remoteSettings, headerEncoder)
    override val writeController = new WriteControllerImpl(this, 64*1024, tailStage)

    override val pingManager: PingManager = new PingManager(this)
    override val sessionFlowControl: SessionFlowControl = new SessionFlowControlImpl(this, flowStrategy)

    override val streamManager: StreamManager = new StreamManagerImpl(this, StreamIdManager(isClient))


    override def state: ConnectionState = Http2ConnectionImpl.this.state


    override def newInboundStream(streamId: Int): Option[LeafBuilder[StreamMessage]] = {
      // TODO: check the state
      inboundStreamBuilder(streamId)
    }


    // Must be called from within the session executor.
    // If an error is provided, a GO_AWAY is written and we wait for the writeController to
    // close the connection. If not, we do it.
    def invokeShutdownWithError(ex: Option[Throwable], phase: String): Unit = {
      if (state != Closed) {
        currentState = Closed

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
            val goawayFrame = Http2FrameSerializer.mkGoAwayFrame(
              streamManager.idManager.lastInboundStream, http2SessionError)
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
      if (currentState == Running) {
        // Don't set the state to draining because we'll do that in `invokeGoaway`

        // Start draining: send a GOAWAY and set a timer to shutdown
        val noError = Http2Exception.NO_ERROR.goaway()
        val someNoError = Some(noError)
        val frame = Http2FrameSerializer.mkGoAwayFrame(streamManager.idManager.lastInboundStream, noError)
        writeController.write(frame)

        // Drain the StreamManager
        val lastHandledStream = Core.streamManager.idManager.lastOutboundStream
        invokeGoaway(lastHandledStream, s"Session draining for duration $gracePeriod")

        // Now set a timer to force closed the session after the expiration
        // if draining takes too long.
        val work = new Runnable {
          def run(): Unit = invokeShutdownWithError(someNoError, s"drainSession($gracePeriod)")
        }
        Execution.scheduler.schedule(work, serialExecutor, gracePeriod)
      }
    }

    override def invokeGoaway(lastHandledOutboundStream: Int, message: String): Unit = {
      if (currentState == Running) {
        currentState = Http2Connection.Draining
        // Drain the `StreamManager` and then the `WriteController`, then close up.
        Core.streamManager.goAway(lastHandledOutboundStream, message)
          .flatMap { _ => writeController.close() }(serialExecutor)
          .onComplete { _ => invokeShutdownWithError(None, "invokeGoaway") }(serialExecutor)
      }
    }
  }

  private[this] def isClosing: Boolean = state match {
    case _: Closing => true
    case _ => false
  }

  /** `Future` which is satisfied when the session is terminated */
  def onClose: Future[Unit] = closedPromise.future

  // Start the session. This entails starting the read loop
  def startSession(): Unit = synchronized {
    if (started) throw new IllegalStateException(s"Session already started")
    started = true
    logger.debug(s"starting session with peer settings $remoteSettings")
    readLoop(BufferTools.emptyBuffer)
  }

  override def quality: Double = {
    if (isClosing || !Core.streamManager.idManager.unusedOutboundStreams) 0.0
    else 1.0 - Core.streamManager.size.toDouble/remoteSettings.maxConcurrentStreams.toDouble
  }

  override def ping: Future[Duration] = {
    val p = Promise[Duration]
    Core.serialExecutor.execute(new Runnable { def run(): Unit =
      p.completeWith(Core.pingManager.ping()) })
    p.future
  }

  override def drainSession(gracePeriod: Duration): Future[Unit] = {
    require(gracePeriod.isFinite())
    Core.serialExecutor.execute(new Runnable {
      def run(): Unit = Core.invokeDrain(gracePeriod)
    })
    closedPromise.future
  }

  /** Get the current state of the `Session` */
  final def state: ConnectionState = currentState

  ////////////////////////////////////////////////////////////////////////

  private[this] def readLoop(remainder: ByteBuffer): Unit = {
    // the continuation must be run in the sessionExecutor
    tailStage.channelRead().onComplete {
      // This completion is run in the sessionExecutor so its safe to
      // mutate the state of the session.
      case Failure(ex) => Core.invokeShutdownWithError(Some(ex), "readLoop-read")
      case Success(next) =>
        logger.debug(s"Read data: $next")
        val data = BufferTools.concatBuffers(remainder, next)

        logger.debug("Handling inbound data.")
        @tailrec
        def go(): Unit = Core.http2Decoder.decodeBuffer(data) match {
          case Halt => () // nop
          case Continue => go()
          case BufferUnderflow => readLoop(data)
          case Error(ex: Http2StreamException) =>
            // If the stream is still active, it will write the RST.
            // Otherwise, we need to do it here.
            Core.streamManager.get(ex.stream) match {
              case Some(stream) =>
                stream.closeWithError(Some(ex))

              case None =>
                val msg = Http2FrameSerializer.mkRstStreamFrame(ex.stream, ex.code)
                Core.writeController.write(msg)
            }

          case Error(ex) =>
            Core.invokeShutdownWithError(Some(ex), "readLoop-decode")
        }
        go()
    }(Core.serialExecutor)
  }
}
