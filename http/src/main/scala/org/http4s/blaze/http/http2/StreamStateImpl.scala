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

package org.http4s.blaze
package http
package http2

import java.nio.ByteBuffer
import java.util
import org.http4s.blaze.http.http2.Http2Exception._
import org.http4s.blaze.pipeline.Command.EOF
import org.http4s.blaze.util.BufferTools
import scala.concurrent.{Future, Promise}

/** Virtual pipeline head for representing HTTP/2 streams
  *
  * It provides the junction for de-multiplexing stream messages into an individual stream. It
  * handles commands and errors for the stream and manages the lifetime in the parent session
  * accordingly.
  *
  * @note
  *   While `StreamState` does enforce the end-stream semantics defined by HTTP/2, it doesn't
  *   attempt to enforce the semantics of the HTTP dispatch, specifically it doesn't enforce that
  *   HEADERS come before DATA, etc, and that duty belongs to the streams dispatcher.
  */
private abstract class StreamStateImpl(session: SessionCore) extends StreamState {
  // State associated with the streams inbound data flow
  private[this] val pendingInboundMessages = new util.ArrayDeque[StreamFrame](1)
  private[this] var pendingRead: Promise[StreamFrame] = null

  // State associated with the streams outbound data flow
  private[this] var writePromise: Promise[Unit] = null
  private[this] var pendingOutboundFrame: StreamFrame = null
  private[this] var interestRegistered = false

  // Guards against registering itself multiple times with the write controller
  private[this] def doRegisterWriteInterest(): Unit =
    if (!interestRegistered) {
      interestRegistered = true
      assert(session.writeController.registerWriteInterest(this))
    }

  // Whether the steam is closed
  private[this] def streamIsClosed: Boolean = closedReason.isDefined

  // Determines if we can receive and send messages
  // WARNING: this should only be set to true in the `closeWithError` handler and
  //          only handled within the session executor
  private[this] var closedReason: Option[Throwable] = None

  // Similar to the state of halfClosedLocal
  // we can no longer send frames other than WINDOW_UPDATE, PRIORITY, and RST_STREAM
  private[this] var sentEndStream: Boolean = false

  // Similar to the state of halfClosedRemote
  // peer can no longer send frames other than WINDOW_UPDATE, PRIORITY, and RST_STREAM
  private[this] var receivedEndStream: Boolean = false

  override def readRequest(size: Int): Future[StreamFrame] = {
    val _ = size
    val p = Promise[StreamFrame]()
    // Move the work into the session executor
    session.serialExecutor.execute(new Runnable {
      override def run(): Unit = invokeStreamRead(p)
    })

    p.future
  }

  private[this] def invokeStreamRead(p: Promise[StreamFrame]): Unit =
    if (pendingRead != null) {
      doCloseWithError(Some(INTERNAL_ERROR.rst(streamId)))
      p.failure(
        new IllegalStateException(s"Already have an outstanding read on a stream ($streamId)"))
      ()
    } else if (streamIsClosed) {
      // `.get` is safe since it must be Some if `streamIsClosed == true`
      p.failure(closedReason.get)
      ()
    } else
      pendingInboundMessages.poll() match {
        case null if receivedEndStream =>
          p.failure(EOF)
          ()

        case null =>
          pendingRead = p
          ()
        case msg =>
          val flowBytes = msg.flowBytes
          if (0 < flowBytes)
            flowWindow.inboundConsumed(flowBytes)
          p.success(msg)
          ()
      }

  final override def writeRequest(msg: StreamFrame): Future[Unit] = {
    val p = Promise[Unit]()
    // Move the work into the session executor
    session.serialExecutor.execute(new Runnable {
      override def run(): Unit = invokeStreamWrite(msg, p)
    })

    p.future
  }

  // Invoke methods are intended to only be called from within the context of the session
  protected def invokeStreamWrite(msg: StreamFrame, p: Promise[Unit]): Unit =
    if (writePromise != null) {
      doCloseWithError(Some(INTERNAL_ERROR.rst(streamId)))
      p.failure(new IllegalStateException(s"Already a pending write on this stream ($streamId)"))
      ()
    } else if (sentEndStream) {
      p.failure(new IllegalStateException(s"Stream($streamId) already closed"))
      ()
    } else if (streamIsClosed) {
      sentEndStream = msg.endStream
      // safe to call `.get` because it must be Some if `streamIsClosed == true`
      p.failure(closedReason.get)
      ()
    } else {
      sentEndStream = msg.endStream
      pendingOutboundFrame = msg
      writePromise = p

      // If this is a flow controlled frame and we can't write any bytes, don't register an interest
      if (msg.flowBytes == 0 || flowWindow.outboundWindowAvailable)
        doRegisterWriteInterest()
    }

  /** Called when the outbound flow window of the session or this stream has had some data acked and
    * we may now be able to make forward progress.
    */
  final override def outboundFlowWindowChanged(): Unit =
    if (writePromise != null && flowWindow.outboundWindowAvailable)
      doRegisterWriteInterest()

  /** Must be called by the [[WriteController]] from within the session executor */
  final override def performStreamWrite(): collection.Seq[ByteBuffer] = {
    interestRegistered = false

    // Nothing waiting to go out, so return fast
    if (writePromise == null) Nil
    else
      pendingOutboundFrame match {
        case HeadersFrame(priority, endStream, hs) =>
          val data =
            session.http2Encoder.headerFrame(streamId, priority, endStream, hs)
          // We consume the whole thing so we now clear out the write channel
          val p = writePromise
          writePromise = null
          pendingOutboundFrame = null
          // TODO: in some ways, this is not accurate since we haven't actually written anything,
          //       just offered it to the `WriteController`.
          p.success(())
          data

        case DataFrame(endStream, data) =>
          val requested =
            math.min(session.remoteSettings.maxFrameSize, data.remaining)
          val allowedBytes = flowWindow.outboundRequest(requested)

          logger.debug(s"Allowed: $allowedBytes, data: $pendingOutboundFrame")

          if (allowedBytes == pendingOutboundFrame.flowBytes) {
            // Writing the whole message
            val buffers =
              session.http2Encoder.dataFrame(streamId, endStream, data)
            val p = writePromise
            writePromise = null
            pendingOutboundFrame = null
            p.success(())
            buffers
          } else if (allowedBytes == 0)
            // Can't make progress, must wait for flow update to proceed.
            // Note: this case must be second since a DataFrame with 0 bytes can be used to signal EOS
            Nil
          else {
            // Can't send all the data right now so we take a chunk, and then again register
            // ourselves with the listener
            val slice = BufferTools.takeSlice(data, allowedBytes)
            val buffers =
              session.http2Encoder.dataFrame(streamId, endStream = false, slice)

            if (flowWindow.streamOutboundWindow > 0)
              // We were not limited by the flow window so signal interest in another write cycle.
              // Note: this won't trigger recursion since the WriteController must not be idle to
              // call the `performStreamWrite` call.
              doRegisterWriteInterest()

            buffers
          }
      }
  }

  // /////////////////// Inbound messages ///////////////////////////////

  final override def invokeInboundData(
      endStream: Boolean,
      data: ByteBuffer,
      flowBytes: Int): MaybeError =
    if (receivedEndStream)
      // https://tools.ietf.org/html/rfc7540#section-5.1 section 'half-closed'
      Error(STREAM_CLOSED.rst(streamId, s"Stream($streamId) received DATA frame after EOS"))
    else if (streamIsClosed)
      // Shouldn't get here: should have been removed from active streams
      Error(STREAM_CLOSED.goaway(s"Stream($streamId) received DATA after stream was closed"))
    else if (flowWindow.inboundObserved(flowBytes)) {
      receivedEndStream = endStream
      val consumed =
        if (queueMessage(DataFrame(endStream, data))) flowBytes
        else flowBytes - data.remaining()
      flowWindow.inboundConsumed(consumed)
      Continue
    } else
      // Inbound flow window violated. Technically, if it was a stream overflow,
      // this could be a stream error, but we don't distinguish which window was
      // violated and the peer is misbehaving, so we just kill the session.
      Error(FLOW_CONTROL_ERROR.goaway(s"stream($streamId) flow control error"))

  // Must be called with a complete headers block, either the prelude or trailers
  final override def invokeInboundHeaders(
      priority: Priority,
      endStream: Boolean,
      headers: Headers): MaybeError =
    if (receivedEndStream)
      // https://tools.ietf.org/html/rfc7540#section-5.1 section 'half-closed'
      Error(STREAM_CLOSED.rst(streamId, s"Stream($streamId received HEADERS frame after EOS"))
    else if (streamIsClosed)
      // Shouldn't get here: should have been removed from active streams
      Error(STREAM_CLOSED.goaway(s"Stream($streamId) received HEADERS after stream was closed"))
    else {
      if (endStream)
        receivedEndStream = true
      queueMessage(HeadersFrame(priority, endStream, headers))
      Continue
    }

  // ////////////////////////////////////////////////////////////////////

  final override protected def doClosePipeline(cause: Option[Throwable]): Unit =
    session.serialExecutor.execute(new Runnable { def run(): Unit = doCloseWithError(cause) })

  // Shuts down the stream and calls `StreamManager.streamFinished` with any potential errors.
  // WARNING: this must be called from within the session executor.
  final override def doCloseWithError(cause: Option[Throwable]): Unit =
    if (!streamIsClosed) {
      closedReason = cause match {
        case None => StreamStateImpl.SomeEOF
        case other => other
      }

      // Release resources, including flow bytes pending
      clearDataChannels(closedReason.get)

      // We need to translate arbitrary exceptions into a Http2Exception
      val http2Ex: Option[Http2Exception] = cause match {
        // Gotta make sure both sides agree that this stream is closed
        case None if !(sentEndStream && receivedEndStream) =>
          Some(CANCEL.rst(streamId))
        case None => None
        case Some(ex: Http2Exception) => Some(ex)
        case Some(other) =>
          logger.warn(other)(s"Unknown error in stream($streamId)")
          Some(INTERNAL_ERROR.rst(streamId, "Unhandled error in stream pipeline"))
      }

      // If we're registered, remove ourselves from the streamManager. If we're
      // not initialized we shouldn't be registered, and won't have a stream id.
      val wasRegistered = initialized && session.streamManager.streamClosed(this)

      http2Ex match {
        case Some(ex: Http2StreamException) if wasRegistered =>
          logger.debug(ex)(s"Sending stream ($streamId) RST")
          val frame = session.http2Encoder.rstFrame(streamId, ex.code)
          session.writeController.write(frame)
          ()

        case Some(ex: Http2StreamException) =>
          // If the stream didn't exist, it is because this stream was never
          // initialized or it was reset by the remote, and thus we shouldn't
          // send a RST ourselves.
          // https://tools.ietf.org/html/rfc7540#section-5.4.2
          logger.debug(ex)(s"Stream ($streamId) closed but not sending RST")
          ()

        case Some(_: Http2SessionException) =>
          logger.info(s"Stream($streamId) finished with session exception")
          session.invokeShutdownWithError(http2Ex, "streamFinished")

        case None => () // nop
      }
    }

  // handle the inbound message.
  // Returns `true` if the message was handled by a stream. Otherwise, it was queued and returns `false`.
  private[this] def queueMessage(msg: StreamFrame): Boolean =
    if (pendingRead == null) {
      pendingInboundMessages.offer(msg)
      false
    } else {
      pendingRead.success(msg)
      pendingRead = null
      true
    }

  private[this] def clearDataChannels(ex: Throwable): Unit = {
    // Clear the read channel
    if (pendingRead == null) {
      var pendingBytes = 0
      while (!pendingInboundMessages.isEmpty)
        pendingBytes += pendingInboundMessages.poll().flowBytes

      flowWindow.sessionFlowControl.sessionInboundConsumed(pendingBytes)
    } else {
      val p = pendingRead
      pendingRead = null
      p.failure(ex)
      ()
    }

    // clear the write channel
    if (writePromise != null) {
      val p = writePromise
      writePromise = null
      pendingOutboundFrame = null
      p.failure(ex)
      ()
    }
  }
}

private object StreamStateImpl {
  // Cache this since it will be a common value
  private val SomeEOF: Some[Throwable] = Some(EOF)
}
