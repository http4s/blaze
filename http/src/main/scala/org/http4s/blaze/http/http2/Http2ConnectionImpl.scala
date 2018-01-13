package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.HttpClientSession
import org.http4s.blaze.http.HttpClientSession.Status
import org.http4s.blaze.http.http2.Http2Connection._
import org.http4s.blaze.pipeline.{Command, HeadStage, LeafBuilder, TailStage}
import org.http4s.blaze.util.BufferTools

import scala.annotation.tailrec
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/** Representation of the http2 session.
  *
  * This is mostly a shell around a concrete [[SessionCore]] implementation.
  */
private class Http2ConnectionImpl(
    isClient: Boolean,
    tailStage: TailStage[ByteBuffer],
    localSettings: Http2Settings, // The settings of this side
    remoteSettings: MutableHttp2Settings, // The peers settings. These can change during the session.
    flowStrategy: FlowStrategy,
    inboundStreamBuilder: Int => Option[LeafBuilder[StreamMessage]],
    parentExecutor: ExecutionContext)
  extends Http2Connection {

  final protected val logger = org.log4s.getLogger

  private[this] var started = false

  private[this] val core = new SessionCoreImpl(
    isClient = isClient,
    tailStage = tailStage,
    localSettings = localSettings,
    remoteSettings = remoteSettings,
    flowStrategy = flowStrategy,
    inboundStreamBuilder = inboundStreamBuilder,
    parentExecutor = parentExecutor
  )

  private[this] def isClosing: Boolean = state match {
    case _: Closing => true
    case _ => false
  }

  /** `Future` which is satisfied when the session is terminated */
//  def onClose: Future[Unit] = closedPromise.future

  // Start the session. This entails starting the read loop
  final def startSession(): Unit = {
    synchronized {
      if (started) throw new IllegalStateException(s"Session already started")
      started = true
    }
    logger.debug(s"starting session with peer settings $remoteSettings")
    readLoop(BufferTools.emptyBuffer)

    // Make sure we disconnect from the reactor once the session is done
    core.onClose.onComplete { _ =>
      tailStage.sendOutboundCommand(Command.Disconnect)
    }(parentExecutor)
  }

  final override def quality: Double = {
    // Note that this is susceptible to memory visibility issues
    // but that's okay since this is intrinsically racy.
    if (isClosing || !core.idManager.unusedOutboundStreams) 0.0
    else 1.0 - (core.streamManager.size.toDouble/remoteSettings.maxConcurrentStreams.toDouble)
  }

  /** Get the status of session */
  final override def status: Status = {
    if (state == Http2Connection.Running) {
      if (core.streamManager.size < remoteSettings.maxConcurrentStreams) {
        HttpClientSession.Ready
      } else {
        HttpClientSession.Busy
      }
    } else {
      HttpClientSession.Closed
    }
  }

  override def ping: Future[Duration] = {
    val p = Promise[Duration]
    core.serialExecutor.execute(new Runnable { def run(): Unit =
      p.completeWith(core.pingManager.ping()) })
    p.future
  }

  final override def drainSession(gracePeriod: Duration): Future[Unit] = {
    require(gracePeriod.isFinite())
    core.serialExecutor.execute(new Runnable {
      def run(): Unit = core.invokeDrain(gracePeriod)
    })
    core.onClose
  }

  /** Create a new outbound stream
    *
    * Resources are not necessarily allocated to this stream, therefore it is
    * not guaranteed to succeed.
    */
  final def newOutboundStream(): HeadStage[StreamMessage] = {
    // TODO: right now this only benefits the client. We need to get the push-promise support for the server side
    core.streamManager.newOutboundStream()
  }


  /** Get the current state of the `Session` */
  final def state: ConnectionState = core.state

  ////////////////////////////////////////////////////////////////////////
  private[this] def readLoop(remainder: ByteBuffer): Unit = {
    // the continuation must be run in the sessionExecutor
    tailStage.channelRead().onComplete {
      // This completion is run in the sessionExecutor so its safe to
      // mutate the state of the session.
      case Failure(ex) => core.invokeShutdownWithError(Some(ex), "readLoop-read")
      case Success(next) =>
        logger.debug(s"Read data: $next")
        val data = BufferTools.concatBuffers(remainder, next)

        logger.debug("Handling inbound data.")
        @tailrec
        def go(): Unit = core.http2Decoder.decodeBuffer(data) match {
          case Halt => () // nop
          case Continue => go()
          case BufferUnderflow => readLoop(data)
          case Error(ex: Http2StreamException) =>
            // If the stream is still active, it will write the RST.
            // Otherwise, we need to do it here.
            core.streamManager.get(ex.stream) match {
              case Some(stream) =>
                stream.closeWithError(Some(ex))

              case None =>
                val msg = Http2FrameSerializer.mkRstStreamFrame(ex.stream, ex.code)
                core.writeController.write(msg)
            }

          case Error(ex) =>
            core.invokeShutdownWithError(Some(ex), "readLoop-decode")
        }
        go()
    }(core.serialExecutor)
  }
}
