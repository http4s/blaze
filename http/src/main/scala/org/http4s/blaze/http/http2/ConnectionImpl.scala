/*
 * Copyright 2014 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.blaze.http.http2

import java.nio.ByteBuffer

import org.http4s.blaze.http.HttpClientSession
import org.http4s.blaze.http.HttpClientSession.Status
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.pipeline.{HeadStage, LeafBuilder, TailStage}
import org.http4s.blaze.util.{BufferTools, Execution, SerialExecutionContext}

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

/** Representation of the HTTP/2 connection.
  *
  * @note
  *   the TailStage needs to be ready to go as this session will start reading from the channel
  *   immediately.
  */
private final class ConnectionImpl(
    tailStage: TailStage[ByteBuffer],
    val localSettings: Http2Settings,
    val remoteSettings: MutableHttp2Settings,
    flowStrategy: FlowStrategy,
    inboundStreamBuilder: Option[Int => LeafBuilder[StreamFrame]],
    parentExecutor: ExecutionContext
) extends SessionCore
    with Connection {
  // Shortcut methods
  private[this] def isClient = inboundStreamBuilder.isEmpty

  private[this] val logger = org.log4s.getLogger
  private[this] val closedPromise = Promise[Unit]()
  private[this] val frameDecoder = new FrameDecoder(
    localSettings,
    new SessionFrameListener(
      this,
      isClient,
      new HeaderDecoder(
        maxHeaderListSize = localSettings.maxHeaderListSize,
        discardOverflowHeaders = true,
        maxTableSize = localSettings.headerTableSize
      )
    )
  )

  @volatile
  private[this] var currentState: Connection.State = Connection.Running
  private[this] var sentGoAway = false

  override val serialExecutor = new SerialExecutionContext(parentExecutor) {
    override def reportFailure(cause: Throwable): Unit =
      invokeShutdownWithError(Some(cause), "SerialExecutor")
  }

  override val http2Encoder =
    new FrameEncoder(remoteSettings, new HeaderEncoder(remoteSettings.maxHeaderListSize))

  override val idManager: StreamIdManager = StreamIdManager(isClient)
  override val writeController =
    new WriteControllerImpl(this, 64 * 1024, tailStage)
  override val pingManager: PingManager = new PingManager(this)
  override val sessionFlowControl: SessionFlowControl =
    new SessionFlowControlImpl(this, flowStrategy)
  override val streamManager: StreamManager =
    new StreamManagerImpl(this, inboundStreamBuilder)

  // start read loop and add shutdown hooks
  readLoop(BufferTools.emptyBuffer)

  // Make sure we disconnect from the reactor once the session is done
  onClose.onComplete(_ => tailStage.closePipeline(None))(parentExecutor)

  private[this] def readLoop(remainder: ByteBuffer): Unit =
    // the continuation must be run in the sessionExecutor
    tailStage
      .channelRead()
      .onComplete {
        // This completion is run in the sessionExecutor so its safe to
        // mutate the state of the session.
        case Failure(ex) => invokeShutdownWithError(Some(ex), "readLoop-read")
        case Success(next) =>
          logger.debug(s"Read data: $next")
          val data = BufferTools.concatBuffers(remainder, next)

          logger.debug("Handling inbound data.")
          @tailrec
          def go(): Unit =
            frameDecoder.decodeBuffer(data) match {
              case Continue => go()
              case BufferUnderflow => readLoop(data)
              case Error(ex: Http2StreamException) =>
                // If the stream is still active, it will write the RST.
                // Otherwise, we need to do it here.
                streamManager.get(ex.stream) match {
                  case Some(stream) =>
                    stream.doCloseWithError(Some(ex))

                  case None =>
                    val msg = FrameSerializer.mkRstStreamFrame(ex.stream, ex.code)
                    writeController.write(msg)
                    ()
                }

              case Error(ex) =>
                invokeShutdownWithError(Some(ex), "readLoop-decode")
            }
          go()
      }(serialExecutor)

  override def quality: Double =
    // Note that this is susceptible to memory visibility issues
    // but that's okay since this is intrinsically racy.
    if (state.closing || !idManager.unusedOutboundStreams) 0.0
    else {
      val maxConcurrent = remoteSettings.maxConcurrentStreams
      val currentStreams = activeStreams
      if (maxConcurrent == 0 || maxConcurrent <= currentStreams) 0.0
      else 1.0 - (currentStreams.toDouble / maxConcurrent.toDouble)
    }

  override def status: Status =
    state match {
      case Connection.Draining => HttpClientSession.Busy
      case Connection.Closed => HttpClientSession.Closed
      case Connection.Running =>
        if (quality == 0.0) HttpClientSession.Busy
        else HttpClientSession.Ready
    }

  override def activeStreams: Int = streamManager.size

  override def ping(): Future[Duration] = {
    val p = Promise[Duration]()
    serialExecutor.execute(new Runnable {
      def run(): Unit = {
        p.completeWith(pingManager.ping())
        ()
      }
    })
    p.future
  }

  override def drainSession(gracePeriod: Duration): Future[Unit] = {
    serialExecutor.execute(new Runnable {
      def run(): Unit = invokeDrain(gracePeriod)
    })
    onClose
  }

  override def newOutboundStream(): HeadStage[StreamFrame] =
    streamManager.newOutboundStream()

  override def onClose: Future[Unit] = closedPromise.future

  override def state: Connection.State = currentState

  // Must be called from within the session executor.
  // If an error is provided, a GOAWAY is written and we wait for the writeController to
  // close the connection. If not, we do it.
  override def invokeShutdownWithError(ex: Option[Throwable], phase: String): Unit =
    if (state != Connection.Closed) {
      currentState = Connection.Closed

      val http2Ex: Option[Http2Exception] = ex match {
        case None | Some(EOF) => None
        case Some(e: Http2Exception) => Some(e)
        case Some(other) =>
          logger.warn(other)(s"Shutting down HTTP/2 with unhandled exception in phase $phase")
          Some(Http2Exception.INTERNAL_ERROR.goaway("Unhandled internal exception"))
      }

      streamManager.forceClose(http2Ex) // Fail hard
      sendGoAway(http2Ex.getOrElse(Http2Exception.NO_ERROR.goaway(s"No Error")))
      writeController
        .close()
        .onComplete { _ =>
          tailStage.closePipeline(None)
          ex match {
            case Some(ex) => closedPromise.failure(ex)
            case None => closedPromise.success(())
          }
        }(serialExecutor)
    }

  override def invokeDrain(gracePeriod: Duration): Unit =
    if (currentState == Connection.Running) {
      // Start draining: send a GOAWAY and set a timer to force shutdown
      val noError = Http2Exception.NO_ERROR.goaway(s"Session draining for duration $gracePeriod")
      sendGoAway(noError)

      // Drain the StreamManager. We are going to reject our own outbound streams too
      doDrain(idManager.lastOutboundStream, noError)

      val work = new Runnable {
        // We already drained so no error necessary
        def run(): Unit =
          invokeShutdownWithError(None, s"drainSession($gracePeriod)")
      }
      // We don't want to leave the timer set since we don't know know long it will live
      val c = Execution.scheduler.schedule(work, serialExecutor, gracePeriod)
      onClose.onComplete(_ => c.cancel())(Execution.directec)
    }

  override def invokeGoAway(lastHandledOutboundStream: Int, error: Http2SessionException): Unit = {
    // We drain all the streams so we send the remote peer a GOAWAY as well
    sendGoAway(Http2Exception.NO_ERROR.goaway(s"Session received GOAWAY with code ${error.code}"))
    doDrain(lastHandledOutboundStream, error)
  }

  private[this] def doDrain(lastHandledOutboundStream: Int, error: Http2SessionException): Unit =
    if (currentState != Connection.Closed) {
      currentState = Connection.Draining
      // Drain the `StreamManager` and then the `WriteController`, then close up.
      streamManager
        .drain(lastHandledOutboundStream, error)
        .flatMap(_ => writeController.close())(serialExecutor)
        .onComplete(_ => invokeShutdownWithError(None, /* unused */ ""))(serialExecutor)
    }

  private[this] def sendGoAway(ex: Http2Exception): Unit =
    if (!sentGoAway) {
      sentGoAway = true
      val frame = FrameSerializer.mkGoAwayFrame(idManager.lastInboundStream, ex)
      writeController.write(frame)
      ()
    }
}
