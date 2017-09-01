package org.http4s.blaze.http.http2

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import org.http4s.blaze.http.Headers
import org.http4s.blaze.http.http2.Http2Connection.{Draining, _}
import org.http4s.blaze.http.http2.Http2Settings.Setting
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.pipeline.{Command, HeadStage, LeafBuilder}
import org.http4s.blaze.util.{BufferTools, Execution, SerialExecutionContext}

import scala.annotation.tailrec
import scala.collection.mutable.HashMap
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/** Representation of the http2 session. */
private abstract class Http2ConnectionImpl(
    isClient: Boolean,
    mySettings: Http2Settings, // The settings of this side
    peerSettings: MutableHttp2Settings, // The peers settings. These can change during the session.
    http2Encoder: Http2FrameEncoder,
    headerDecoder: HeaderDecoder,
    flowStrategy: FlowStrategy,
    executor: ExecutionContext)
  extends Http2Connection {

  private[this] case class PingState(startedSystemTimeMs: Long, continuation: Promise[Duration])

  @volatile
  private[this] var currentState: ConnectionState = Running
  private[this] var started = false
  private[this] var currentPing: Option[PingState] = None
  // used to signal that the session is closed
  private[this] val closedPromise = Promise[Unit]

  private[this] def isClosing: Boolean = state match {
    case _: Closing => true
    case _ => false
  }

  // The sessionExecutor is responsible for serializing all mutations of the session state,
  // to include its interactions with the socket.
  private[this] val sessionExecutor = new SerialExecutionContext(executor) {
    override def reportFailure(cause: Throwable): Unit = {
      // Any uncaught exceptions in the serial executor are fatal to the session
      invokeShutdownWithError(Some(cause), "sessionExecutor")
    }
  }

  protected val logger: org.log4s.Logger

  // TODO: What about peer information? Streams may care about that, especially for security reasons.
  // Need to be able to create new stream pipelines
  protected def newInboundStream(streamId: Int): Option[LeafBuilder[StreamMessage]]

  // Need to be able to write data to the pipeline
  protected def writeBytes(data: Seq[ByteBuffer]): Future[Unit]

  // Need to be able to read data
  protected def readData(): Future[ByteBuffer]

  // Called when the session has completed all its operations
  protected def sessionTerminated(): Unit

  final protected def activeStreamCount: Int = activeStreams.size

  /** Create a new decoder wrapping this Http2FrameHandler */
  protected def newHttp2Decoder(handler: Http2FrameHandler): Http2FrameDecoder

  /** Acquire a new `HeadStage[StreamMessage]` that will be a part of this `Session`.
    *
    * @note The resulting stage acquires new stream id's lazily to comply with protocol
    *       requirements, and thus is not assigned a valid stream id until the first
    *       `HeadersFrame` has been sent, which may fail.
    */
  final def newOutboundStream(): HeadStage[StreamMessage] = new OutboundStreamState

  // Start the session. This entails starting the read loop
  def startSession(): Unit = synchronized {
    if (started) throw new IllegalStateException(s"Session already started")
    started = true
    logger.debug(s"starting session with peer settings $peerSettings")
    readLoop(BufferTools.emptyBuffer)
  }

  override def quality: Double = {
    if (isClosing || !idManager.unusedOutboundStreams) 0.0
    else 1.0 - activeStreamCount.toDouble/peerSettings.maxConcurrentStreams.toDouble
  }

  override def ping: Future[Duration] = {
    val p = Promise[Duration]
    sessionExecutor.execute(new Runnable { def run(): Unit = handlePing(p) })
    p.future
  }

  // Must be called from within the sessionExecutor
  private[this] def handlePing(p: Promise[Duration]): Unit = currentPing match {
    case Some(_) =>
      p.tryFailure(new IllegalStateException("Ping already in progress"))
      ()

    case None =>
      val time = System.currentTimeMillis
      currentPing = Some(PingState(time, p))
      val data = new Array[Byte](8)
      ByteBuffer.wrap(data).putLong(time)
      val pingFrame = http2Encoder.pingFrame(data)
      writeController.writeOutboundData(pingFrame)
  }

  override def drainSession(gracePeriod: Duration): Future[Unit] = {
    require(gracePeriod.isFinite())
    sessionExecutor.execute(new Runnable {
      def run(): Unit = handleDrainSession(gracePeriod)
    })
    closedPromise.future
  }

  // must be called from within the sessionExecutor
  private[this] def handleDrainSession(gracePeriod: Duration): Unit = {
    val deadline = System.currentTimeMillis + gracePeriod.toMillis
    state match {
      case Closed => () // nop already under control
      case Draining(d) if d < deadline => ()

      case _ =>
        // Start draining: send a GOAWAY and set a timer to shutdown
        val noError = Http2Exception.NO_ERROR.goaway()
        val someNoError = Some(noError)
        currentState = Http2Connection.Draining(deadline)

        val frame = Http2FrameSerializer.mkGoAwayFrame(idManager.lastInboundStream, noError)
        writeController.writeOutboundData(frame)

        // Set a timer to force closed the session after the expiration
        val work = new Runnable {
          def run(): Unit = invokeShutdownWithError(someNoError, s"drainSession($gracePeriod)")
        }
        Execution.scheduler.schedule(work, sessionExecutor, gracePeriod)
        ()
    }
  }

  /** Get the current state of the `Session` */
  final def state: ConnectionState = currentState

  private[this] val sessionFlowControl = new SessionFlowControl(mySettings, peerSettings) {
    override protected def onSessonBytesConsumed(consumed: Int): Unit = {
      val update = flowStrategy.checkSession(this)
      if (update > 0) {
        sessionInboundAcked(update)
        writeController.writeOutboundData(http2Encoder.sessionWindowUpdate(update))
      }
    }

    override protected def onStreamBytesConsumed(stream: StreamFlowWindow, consumed: Int): Unit = {
      val update = flowStrategy.checkStream(this, stream)
      if (update.session > 0) {
        sessionInboundAcked(update.session)
        writeController.writeOutboundData(http2Encoder.sessionWindowUpdate(update.session))
      }

      if (update.stream > 0) {
        stream.streamInboundAcked(update.stream)
        writeController.writeOutboundData(http2Encoder.streamWindowUpdate(stream.streamId, update.stream))
      }
    }
  }

  private[this] val idManager = StreamIdManager(isClient)

  private[this] val activeStreams = new HashMap[Int, Http2StreamStateBase]

  // This must be instantiated last since it has a dependency of `activeStreams` and `idManager`.
  private[this] val http2Decoder = newHttp2Decoder(new SessionFrameHandlerImpl)

  // TODO: thread the write buffer size in as a parameter
  private[this] val writeController = new WriteController(64*1024) {
    /** Write the outbound data to the pipeline */
    override protected def writeToWire(data: Seq[ByteBuffer]): Unit = {
      writeBytes(data).onComplete {
        case Success(_) =>
          // Finish the write cycle, and then see if we need to shutdown the connection.
          // If the connection state is already `Closed`, it means we were flushing a
          // GOAWAY and its our responsibility to signal that the session is now closed.
          writeSuccessful()
          currentState match {
            case Draining(_) if !awaitingWriteFlush && activeStreams.isEmpty =>
              invokeShutdownWithError(None, "writeController.finish draining")

            case Closed if !awaitingWriteFlush =>
              sessionTerminated()

            case _ => ()
          }

        case Failure(t) =>
          if (state == Closed) sessionTerminated()
          else invokeShutdownWithError(Some(t), "writeToWire")
      }(sessionExecutor)
    }
  }

  private class SessionFrameHandlerImpl extends SessionFrameHandler[Http2StreamStateBase](
      mySettings, headerDecoder, activeStreams, sessionFlowControl, idManager) {

    override protected def handlePushPromise(streamId: Int, promisedId: Int, headers: Headers): Http2Result = {
      // TODO: support push promises
      val frame = Http2FrameSerializer.mkRstStreamFrame(promisedId, Http2Exception.REFUSED_STREAM.code)
      writeController.writeOutboundData(frame)
      Continue
    }

    override def onPingFrame(ack: Boolean, data: Array[Byte]): Http2Result = {
      if (!ack) writeController.writeOutboundData(http2Encoder.pingAck(data))
      else currentPing match {
        case None => // nop
        case Some(PingState(sent, continuation)) =>
          currentPing = None

          if (ByteBuffer.wrap(data).getLong != sent) { // data guaranteed to be 8 bytes
            continuation.tryFailure(new Exception("Received ping response with unknown data."))
          } else {
            val duration = Duration.fromNanos((System.currentTimeMillis - sent) * 1000000)
            continuation.trySuccess(duration)
          }
      }

      Continue
    }

    override protected def newInboundStream(streamId: Int): Option[Http2StreamStateBase] = {
      if (isClosing) None
      else Http2ConnectionImpl.this.newInboundStream(streamId).map { builder =>
        val streamFlowWindow = sessionFlowControl.newStreamFlowWindow(streamId)
        logger.debug(s"Created new InboundStream with id $streamId. $activeStreamCount streams.")
        val head = new InboundStreamState(streamId, streamFlowWindow)
        activeStreams.put(streamId, head)
        builder.base(head)
        head.sendInboundCommand(Command.Connected)
        head
      }
    }

    override def onSettingsFrame(ack: Boolean, settings: Seq[Setting]): Http2Result = {
      // We don't consider the uncertainty between us sending a SETTINGS frame and its ack
      // but that's OK since we never update settings after the initial handshake.
      if (ack) Continue
      else {
        var result: MaybeError = Continue

        // These two settings require some action if they change
        val initialInitialWindowSize = peerSettings.initialWindowSize
        val initialHeaderTableSize = peerSettings.headerTableSize

        MutableHttp2Settings.updateSettings(peerSettings, settings) match {
          case Some(ex) => result = Error(ex) // problem with settings
          case None =>

            if (peerSettings.headerTableSize != initialHeaderTableSize) {
              http2Encoder.setMaxTableSize(peerSettings.headerTableSize)
            }

            val diff = peerSettings.initialWindowSize - initialInitialWindowSize
            if (diff != 0) {
              // https://tools.ietf.org/html/rfc7540#section-6.9.2
              // a receiver MUST adjust the size of all stream flow-control windows that
              // it maintains by the difference between the new value and the old value.
              activeStreams.values.forall { stream =>
                val e = stream.flowWindow.peerSettingsInitialWindowChange(diff)
                if (e != Continue && result == Continue) {
                  result = e
                  false
                } else {
                  stream.outboundFlowWindowChanged()
                  true
                }
              }
            }
        }

        if (result == Continue) {
          // ack the SETTINGS frame on success, otherwise we are tearing down the connection anyway so no need
          writeController.writeOutboundData(Http2FrameSerializer.mkSettingsAckFrame())
        }

        result
      }
    }

    override def onGoAwayFrame(lastStream: Int, errorCode: Long, debugData: ByteBuffer): Http2Result = {
      val message = StandardCharsets.UTF_8.decode(debugData).toString
      logger.debug {
        val errorName = Http2Exception.errorName(errorCode)
        val errorCodeStr = s"0x${java.lang.Long.toHexString(errorCode)}: $errorName"
        s"Received GOAWAY($errorCodeStr, '$message'). Last processed stream: $lastStream"
      }

      val unhandledStreams = activeStreams.filterKeys { id =>
        idManager.isOutboundId(id) && id > lastStream
      }

      unhandledStreams.foreach { case (id, stream) =>
        // We remove the stream first so that we don't send a RST back to
        // the peer, since they have discarded the stream anyway.
        activeStreams.remove(id)
        val ex = Http2Exception.REFUSED_STREAM.rst(id, message)
        stream.closeWithError(Some(ex))
      }

      if (activeStreams.isEmpty) {
        invokeShutdownWithError(None, "onGoAwayFrame")
      } else {
        // Need to wait for the streams to close down
        currentState = Draining(Long.MaxValue)
      }

      Continue
    }
  }

  ////////////////////////////////////////////////////////////////////////

  private[this] def readLoop(remainder: ByteBuffer): Unit =
    // the continuation must be run in the sessionExecutor
    readData().onComplete {
      // This completion is run in the sessionExecutor so its safe to
      // mutate the state of the session.
      case Failure(ex) => invokeShutdownWithError(Some(ex), "readLoop-read")
      case Success(next) =>
        logger.debug(s"Read data: $next")
        val data = BufferTools.concatBuffers(remainder, next)

        logger.debug("Handling inbound data.")
        @tailrec
        def go(): Unit = http2Decoder.decodeBuffer(data) match {
          case Halt => () // nop
          case Continue => go()
          case BufferUnderflow => readLoop(data)
          case Error(ex: Http2StreamException) =>
            // If the stream is still active, it will write the RST.
            // Otherwise, we need to do it here.
            activeStreams.get(ex.stream) match {
              case Some(stream) => stream.closeWithError(Some(ex))
              case None =>
                val msg = Http2FrameSerializer.mkRstStreamFrame(ex.stream, ex.code)
                writeController.writeOutboundData(msg)
            }

          case Error(ex) =>
            invokeShutdownWithError(Some(ex), "readLoop-decode")
        }
        go()
    }(sessionExecutor)

  // Must be called from within the session executor.
  // If an error is provided, a GO_AWAY is written and we wait for the writeController to
  // close the connection. If not, we do it.
  private[this] def invokeShutdownWithError(ex: Option[Throwable], phase: String): Unit = {
    if (state != Closed) {
      currentState = Closed

      def closeAllSessions(ex: Option[Throwable]): Unit = {
        val streams = activeStreams.values.toVector
        for (stream <- streams) {
          activeStreams.remove(stream.streamId)
          stream.closeWithError(ex)
        }
      }

      ex match {
        case None | Some(EOF) =>
          closeAllSessions(None)
          try sessionTerminated()  // If we're not writing any more data, we need to terminate the session
          finally {
            closedPromise.trySuccess(())
            ()
          }

        case Some(ex) =>
          val http2SessionError = ex match {
            case ex: Http2Exception =>
              logger.debug(ex)(s"Shutting down with HTTP/2 session in phase $phase")
              ex

            case other =>
              logger.warn(other)(s"Shutting down HTTP/2 with unhandled exception in phase $phase")
              Http2Exception.INTERNAL_ERROR.goaway("Unhandled internal exception")
          }

          closeAllSessions(Some(ex))
          val goawayFrame = Http2FrameSerializer.mkGoAwayFrame(idManager.lastInboundStream, http2SessionError)
          // on completion of the write `sessionTerminated()` should be called
          writeController.writeOutboundData(goawayFrame)
      }
    }
  }

  ////////////////////////////////////////////////////////////////////////////

  private abstract class Http2StreamStateBase
    extends Http2StreamState(writeController, http2Encoder, sessionExecutor) {

    final override protected def maxFrameSize: Int = peerSettings.maxFrameSize

    // Remove closed streams from the session and deal with stream related errors.
    // Note: if the stream has already been removed from the active streams, no message are written to the peer.
    final override protected def onStreamFinished(ex: Option[Http2Exception]): Unit = {
      if (activeStreams.remove(streamId).isDefined) ex match {
        case None => // nop
        case Some(ex: Http2StreamException) =>
          val rstFrame = Http2FrameSerializer.mkRstStreamFrame(ex.stream, ex.code)
          writeController.writeOutboundData(rstFrame)

        case Some(ex: Http2SessionException) =>
          invokeShutdownWithError(Some(ex), "onStreamFinished")
      }

      // Check to see if we were draining and have finished, and if so, are all the streams closed.
      currentState match {
        case Draining(_) if activeStreams.isEmpty && !writeController.awaitingWriteFlush =>
          // This will close down shop, but we need to finish the promise
          invokeShutdownWithError(None, "onStreamFinished")

        case _ => ()
      }
    }
  }

  private class InboundStreamState(
      val streamId: Int,
      val flowWindow: StreamFlowWindow)
    extends Http2StreamStateBase

  /** The trick with the outbound stream is that we need to lazily acquire a streamId
    * since it is possible to acquire a stream but delay writing the headers, which
    * signal the start of the stream.
    */
  private class OutboundStreamState extends Http2StreamStateBase {
    // We don't initialized the streamId and flowWindow until we've sent the first frame. As
    // with most things in this file, these should only be modified from within the session executor
    private[this] var lazyStreamId: Int = -1
    private[this] var lazyFlowWindow: StreamFlowWindow = null

    override def streamId: Int = {
      if (lazyStreamId != -1) lazyStreamId
      else throw new IllegalStateException("Stream uninitialized")
    }

    override def flowWindow: StreamFlowWindow = {
      if (lazyFlowWindow != null) lazyFlowWindow
      else throw new IllegalStateException("Stream uninitialized")
    }

    // We need to establish whether the stream has been initialized yet and try to acquire a new ID if not
    override protected def invokeStreamWrite(msg: StreamMessage, p: Promise[Unit]): Unit = {
      if (lazyStreamId != -1) {
        super.invokeStreamWrite(msg, p)
      } else if (state.isInstanceOf[Closing]) {
        // Before we initialized the stream, we began to drain or were closed.
        val ex = Http2Exception.REFUSED_STREAM.goaway("Session closed before stream was initialized")
        p.tryFailure(ex)
        ()
      } else {
        idManager.takeOutboundId() match {
          case Some(streamId) =>
            lazyStreamId = streamId
            lazyFlowWindow = sessionFlowControl.newStreamFlowWindow(streamId)
            assert(activeStreams.put(streamId, this).isEmpty)
            logger.debug(s"Created new OutboundStream with id $streamId. $activeStreamCount streams.")
            super.invokeStreamWrite(msg, p)

          case None =>
            // TODO: Out of stream IDs. We need to switch to draining
            val ex = Http2Exception.REFUSED_STREAM.rst(0, "Session is out of outbound stream IDs")
            p.tryFailure(ex)
            ()
        }
      }
    }

    override def name: String = {
      // This is a bit racy since it can be called from anywhere, but it's for diagnostics anyway
      if (lazyStreamId != -1) super.name
      else "UnaquiredStreamState"
    }
  }
}
