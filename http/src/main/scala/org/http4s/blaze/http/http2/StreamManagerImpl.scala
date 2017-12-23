package org.http4s.blaze.http.http2

import org.http4s.blaze.http._

import scala.collection.mutable.HashMap
import scala.concurrent.{Future, Promise}

private final class StreamManagerImpl(
  session: SessionCore,
  val idManager: StreamIdManager
) extends StreamManager {
  private[this] val logger = org.log4s.getLogger
  private[this] val streams = new HashMap[Int, StreamState]

  private[this] var drainingP: Option[Promise[Unit]] = None

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
      stream.flowWindow.peerSettingsInitialWindowChange(diff) match {
        case Continue =>
          stream.outboundFlowWindowChanged()

        case Error(ex) =>
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
      stream.closeWithError(cause)
    }

    // Need to set the closed state
    drainingP match {
      case Some(p) =>
        p.trySuccess(())
        ()

      case None =>
        val p = Promise[Unit]
        p.trySuccess(())
        drainingP = Some(p)
    }
  }

  override def registerInboundStream(state: InboundStreamState): Boolean = {
    if (drainingP.isDefined) false
    else if (!idManager.observeInboundId(state.streamId)) false
    else {
      // if we haven't observed the stream yet, we know that its not in the map
      assert(streams.put(state.streamId, state).isEmpty)
      true
    }
  }

  override def registerOutboundStream(state: OutboundStreamState): Option[Int] = {
    if (drainingP.isDefined) None
    else {
      val id = idManager.takeOutboundId()
      id match {
        case Some(streamId) => assert(streams.put(streamId, state).isEmpty)
        case None => ()
      }
      id
    }
  }

  override def rstStream(cause: Http2StreamException): Boolean = {
    // We remove the stream from the collection first since the `StreamState` will
    // then invoke `streamClosed` and use it's return value as the predicate for
    // sending a RST, which it shouldn't do since it was closed via an RST.
    // https://tools.ietf.org/html/rfc7540#section-5.4.2
    streams.remove(cause.stream) match {
      case Some(streamState) =>
        streamState.closeWithError(Some(cause))
        true

      case None =>
        false
    }
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

  override def handlePushPromise(streamId: Int, promisedId: Int, headers: Headers): Http2Result = {
    // TODO: support push promises
    // validating the stream ID's is handled by the `SessionFrameListener`
    logger.debug(s"Rejecting pushed stream $promisedId associated with stream $streamId")
    val frame = session.http2Encoder.rstFrame(promisedId, Http2Exception.REFUSED_STREAM.code)
    session.writeController.write(frame)
    Continue
  }

  override def flowWindowUpdate(streamId: Int, sizeIncrement: Int): MaybeError = {
    if (streamId == 0) {
      val result = session.sessionFlowControl.sessionOutboundAcked(sizeIncrement)
      logger.debug(s"Session flow update: $sizeIncrement. Result: $result")
      if (result.success) {
        // TODO: do we need to wake all the open streams in every case? Maybe just when we go from 0 to > 0?
        streams.values.foreach(_.outboundFlowWindowChanged())
      }
      result
    } else {
      streams.get(streamId) match {
        case None =>
          logger.debug(s"Stream WINDOW_UPDATE($sizeIncrement) for closed stream $streamId")
          Continue // nop

        case Some(stream) =>
          val result = stream.flowWindow.streamOutboundAcked(sizeIncrement)
          logger.debug(s"Stream(${stream.streamId}) WINDOW_UPDATE($sizeIncrement). Result: $result")

          if (result.success) {
            stream.outboundFlowWindowChanged()
          }

          result
      }
    }
  }

  override def goAway(lastHandledOutboundStream: Int, message: String): Future[Unit] = {
    drainingP match {
      case Some(p) =>
        logger.debug(s"Received a second GOAWAY($lastHandledOutboundStream, $message")
        p.future

      case None =>
        logger.debug(s"StreamManager.goaway($lastHandledOutboundStream, $message)")
        val unhandledStreams = streams.filterKeys { id =>
          lastHandledOutboundStream < id && idManager.isOutboundId(id)
        }

        unhandledStreams.foreach { case (id, stream) =>
          // We remove the stream first so that we don't send a RST back to
          // the peer, since they have discarded the stream anyway.
          streams.remove(id)
          val ex = Http2Exception.REFUSED_STREAM.rst(id, message)
          stream.closeWithError(Some(ex))
        }

        val p = Promise[Unit]
        drainingP = Some(p)
        p.future
    }
  }
}
