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

import org.http4s.blaze.http._
import org.http4s.blaze.http.http2.Http2Exception.{PROTOCOL_ERROR, REFUSED_STREAM}
import org.http4s.blaze.pipeline.{Command, LeafBuilder}
import scala.collection.mutable.HashMap
import scala.concurrent.{Future, Promise}

private final class StreamManagerImpl(
    session: SessionCore,
    inboundStreamBuilder: Option[Int => LeafBuilder[StreamFrame]]
) extends StreamManager {
  private[this] val logger = org.log4s.getLogger
  private[this] val streams = new HashMap[Int, StreamState]

  private[this] var drainingP: Option[Promise[Unit]] = None

  private class OutboundStreamStateImpl extends http2.OutboundStreamStateImpl(session) {
    override protected def registerStream(): Option[Int] =
      if (drainingP.isDefined) None // We're draining, so reject the stream
      else
        session.idManager.takeOutboundId() match {
          case id @ Some(freshId) =>
            assert(streams.put(freshId, this).isEmpty)
            id

          case None =>
            None
        }
  }

  // TODO: we use this to determine status, and that isn't thread safe
  override def size: Int = streams.size

  override def isEmpty: Boolean = streams.isEmpty

  // https://tools.ietf.org/html/rfc7540#section-6.9.2
  // a receiver MUST adjust the size of all stream flow-control windows that
  // it maintains by the difference between the new value and the old value.
  override def initialFlowWindowChange(diff: Int): MaybeError = {
    var result: MaybeError = Continue
    val it = streams.valuesIterator
    while (it.hasNext && result == Continue) {
      val stream = it.next()
      stream.flowWindow.remoteSettingsInitialWindowChange(diff) match {
        case None =>
          stream.outboundFlowWindowChanged()

        case Some(ex) =>
          // We shouldn't get stream exceptions here, only a
          // GO_AWAY of type FLOW_CONTROL_ERROR
          result = Error(ex.toSessionException())
      }
    }
    result
  }

  override def get(id: Int): Option[StreamState] =
    streams.get(id)

  override def forceClose(cause: Option[Throwable]): Unit = {
    val ss = streams.values.toVector
    for (stream <- ss) {
      streams.remove(stream.streamId)
      stream.doCloseWithError(cause)
    }

    // Need to set the closed state
    drainingP match {
      case Some(p) =>
        p.trySuccess(())
        ()

      case None =>
        val p = Promise[Unit]()
        p.trySuccess(())
        drainingP = Some(p)
    }
  }

  def newInboundStream(streamId: Int): Either[Http2Exception, InboundStreamState] =
    if (!session.idManager.observeInboundId(streamId)) {
      // Illegal stream ID. See why below
      val msg =
        if (session.idManager.isOutboundId(streamId))
          s"Received HEADERS frame for idle outbound stream id $streamId"
        else
          s"Received HEADERS frame for non-idle inbound stream id $streamId"
      Left(PROTOCOL_ERROR.goaway(msg))
    } else if (inboundStreamBuilder.isEmpty)
      Left(
        PROTOCOL_ERROR.goaway(
          s"Client received request for new inbound stream ($streamId) without push promise"))
    else if (drainingP.isDefined)
      Left(REFUSED_STREAM.rst(streamId, "Session draining"))
    else if (session.localSettings.maxConcurrentStreams <= size)
      // 5.1.2 Stream Concurrency
      //
      // Endpoints MUST NOT exceed the limit set by their peer.  An endpoint
      // that receives a HEADERS frame that causes its advertised concurrent
      // stream limit to be exceeded MUST treat this as a stream error
      // (Section 5.4.2) of type PROTOCOL_ERROR or REFUSED_STREAM.  The choice
      // of error code determines whether the endpoint wishes to enable
      // automatic retry (see Section 8.1.4) for details).
      Left(REFUSED_STREAM.rst(streamId))
    else {
      val leafBuilder =
        inboundStreamBuilder.get.apply(streamId) // we already made sure it wasn't empty
      val streamFlowWindow =
        session.sessionFlowControl.newStreamFlowWindow(streamId)
      val streamState =
        new InboundStreamStateImpl(session, streamId, streamFlowWindow)
      assert(streams.put(streamId, streamState).isEmpty)

      // assemble the pipeline
      leafBuilder.base(streamState)
      streamState.sendInboundCommand(Command.Connected)

      Right(streamState)
    }

  override def rstStream(cause: Http2StreamException): MaybeError =
    // We remove the stream from the collection first since the `StreamState` will
    // then invoke `streamClosed` and use it's return value as the predicate for
    // sending a RST, which it shouldn't do since it was closed via an RST.
    // https://tools.ietf.org/html/rfc7540#section-5.4.2
    streams.remove(cause.stream) match {
      case Some(streamState) =>
        streamState.doCloseWithError(Some(cause))
        Continue

      case None if session.idManager.isIdleId(cause.stream) =>
        Error(PROTOCOL_ERROR.goaway(s"RST_STREAM for idle stream id ${cause.stream}"))

      case None =>
        Continue // nop
    }

  override def streamClosed(streamState: StreamState): Boolean = {
    val result = streams.remove(streamState.streamId).isDefined

    // See if we've drained all the streams
    drainingP match {
      case Some(p) if streams.isEmpty =>
        p.trySuccess(())
        ()
      case _ => () // nop
    }

    result
  }

  override def handlePushPromise(streamId: Int, promisedId: Int, headers: Headers): Result =
    if (session.idManager.isIdleOutboundId(streamId))
      Error(
        PROTOCOL_ERROR.goaway(
          s"Received PUSH_PROMISE for associated to an idle stream ($streamId)"))
    else if (!session.idManager.isInboundId(promisedId))
      Error(
        PROTOCOL_ERROR.goaway(s"Received PUSH_PROMISE frame with illegal stream id: $promisedId"))
    else if (!session.idManager.observeInboundId(promisedId))
      Error(PROTOCOL_ERROR.goaway("Received PUSH_PROMISE frame on non-idle stream"))
    else {
      // TODO: support push promises
      // validating the stream ID's is handled by the `SessionFrameListener`.
      // We can't just mark the promised stream ID as observed since the next
      // thing the server will send will be headers, and we'd interpret that as
      // a protocol error. We need to keep a set of promised ID's, but don't want
      // to deal with the book keeping and possible resource leakage since at some
      // point we need to tenure them to just closed, but that will be racy.
      // So, we take the trusting way out and just leave the stream ID available
      // and presume that the server will either send a HEADERS frame which we will
      // cancel, or just not use the ID.
      // The race makes it impossible to do absolutely correct, but a reasonable
      // more-robust solution is to keep a set of promised ID's which we clean up
      // after some expiration period after which we assume the server isn't going
      // to send the HEADERS of the associated response.
      logger.debug(s"Rejecting pushed stream $promisedId associated with stream $streamId")
      Error(REFUSED_STREAM.rst(promisedId, "Server push not supported"))
    }

  override def flowWindowUpdate(streamId: Int, sizeIncrement: Int): MaybeError =
    // note: No need to check the size increment since it's been
    // validated by the `Http2FrameDecoder`.
    if (session.idManager.isIdleId(streamId))
      Error(PROTOCOL_ERROR.goaway(s"WINDOW_UPDATE on uninitialized stream ($streamId)"))
    else if (streamId == 0) {
      val result = MaybeError(session.sessionFlowControl.sessionOutboundAcked(sizeIncrement))
      logger.debug(s"Session flow update: $sizeIncrement. Result: $result")
      if (result.success)
        // TODO: do we need to wake all the open streams in every case? Maybe just when we go from 0 to > 0?
        streams.values.foreach(_.outboundFlowWindowChanged())
      result
    } else
      streams.get(streamId) match {
        case None =>
          logger.debug(s"Stream WINDOW_UPDATE($sizeIncrement) for closed stream $streamId")
          Continue // nop

        case Some(stream) =>
          val result = MaybeError(stream.flowWindow.streamOutboundAcked(sizeIncrement))
          logger.debug(s"Stream(${stream.streamId}) WINDOW_UPDATE($sizeIncrement). Result: $result")

          if (result.success)
            stream.outboundFlowWindowChanged()

          result
      }

  override def drain(lastHandledOutboundStream: Int, reason: Http2SessionException): Future[Unit] =
    drainingP match {
      case Some(p) =>
        logger.debug(reason)(s"Received a second GOAWAY($lastHandledOutboundStream")
        p.future

      case None =>
        logger.debug(reason)(s"StreamManager.goaway($lastHandledOutboundStream)")

        streams.foreach {
          case (id, stream)
              if lastHandledOutboundStream < id && session.idManager.isOutboundId(id) =>
            // We remove the stream first so that we don't send a RST back to
            // the peer, since they have discarded the stream anyway.
            streams.remove(id)
            val ex = Http2Exception.REFUSED_STREAM.rst(id, reason.msg)
            stream.doCloseWithError(Some(ex))
          case _ =>
          // Working around change to filterKeys in 2.13
        }

        val p = Promise[Unit]()
        drainingP = Some(p)
        p.future
    }

  override def newOutboundStream(): OutboundStreamState =
    new OutboundStreamStateImpl
}
