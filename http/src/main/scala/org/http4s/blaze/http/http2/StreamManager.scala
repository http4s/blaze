package org.http4s.blaze.http.http2

import org.http4s.blaze.http._

import scala.collection.mutable.HashMap
import scala.concurrent.{Future, Promise}

private class StreamManager(
  session: SessionCore,
  val idManager: StreamIdManager
) {
  private[this] val logger = org.log4s.getLogger
  private[this] val streams = new HashMap[Int, StreamState]

  private[this] var drainingP: Option[Promise[Unit]] = None

  // TODO: we use this to determine status, and that isn't thread safe
  def size: Int = streams.size

  def isEmpty: Boolean = streams.isEmpty

  // https://tools.ietf.org/html/rfc7540#section-6.9.2
  // a receiver MUST adjust the size of all stream flow-control windows that
  // it maintains by the difference between the new value and the old value.
  def initialFlowWindowChange(diff: Int): MaybeError = {
    var result: MaybeError = Continue
    streams.values.forall { stream =>
      val e = stream.flowWindow.peerSettingsInitialWindowChange(diff)
      if (e != Continue && result == Continue) {
        result = e
        false
      } else {
        stream.outboundFlowWindowChanged()
        true
      }
    }
    result
  }

  final def get(id: Int): Option[StreamState] =
    streams.get(id)

  final def close(cause: Option[Throwable]): Unit = {
    // Need to set the closed state
    drainingP match {
      case Some(p) =>
        p.trySuccess(())

      case None =>
        val p = Promise[Unit]
        p.trySuccess(())
        drainingP = Some(p)
    }

    val ss = streams.values.toVector
    for (stream <- ss) {
      streams.remove(stream.streamId)
      stream.closeWithError(cause)
    }
  }

  def registerInboundStream(state: InboundStreamState): Boolean = {
    if (drainingP.isDefined) false
    else if (!idManager.observeInboundId(state.streamId)) false
    else {
      // if we haven't observed the stream yet, we know that its not in the map
      assert(streams.put(state.streamId, state).isEmpty)
      true
    }
  }

  def registerOutboundStream(state: OutboundStreamState): Option[Int] = {
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

  /** Close the specified stream
    *
    * @param id stream-id
    * @param cause reason for closing the stream
    * @return true if the stream existed and was closed, false otherwise
    */
  def closeStream(id: Int, cause: Option[Throwable]): Boolean = {
    logger.debug(s"Closing stream ($id): $cause")
    streams.remove(id) match {
      case Some(stream) =>
        // It is the streams job to call `streamFinished`
        stream.closeWithError(cause)
        true

      case None =>
        false
    }
  }

  /** Called by a [[StreamState]] to signal that it is finished.
    *
    * @param stream
    * @param cause
    */
  final def streamFinished(stream: StreamState, cause: Option[Http2Exception]): Unit = {
    val streamId = stream.streamId
    val wasInMap = streams.remove(streamId).isDefined

    cause match {
      case Some(ex: Http2StreamException) if wasInMap =>
        logger.debug(ex)(s"Sending stream ($streamId) RST")
        val frame = session.http2Encoder.rstFrame(streamId, ex.code)
        session.writeController.write(frame)

      case Some(ex: Http2StreamException) =>
        logger.debug(ex)(s"Stream($streamId) closed with RST, not sending reply")

      case Some(ex: Http2SessionException) =>
        logger.info(s"Stream($streamId) finished with session exception")
        session.invokeShutdownWithError(cause, "streamFinished")

      case None => // nop
    }

    // See if we've drained all the streams
    drainingP match {
      case Some(p) if streams.isEmpty => p.trySuccess(())
      case _ => () // nop
    }
  }

  /** Handle a valid and complete PUSH_PROMISE frame */
  final def handlePushPromise(streamId: Int, promisedId: Int, headers: Headers): Http2Result = {
    // TODO: support push promises
    val frame =session.http2Encoder.rstFrame(promisedId, Http2Exception.REFUSED_STREAM.code)
    session.writeController.write(frame)
    Continue
  }

  final def windowUpdate(streamId: Int, sizeIncrement: Int): MaybeError = {
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

  final def goaway(lastHandledOutboundStream: Int, message: String): Future[Unit] = {
    drainingP match {
      case Some(p) =>
        logger.debug(s"Received a second GOAWAY($lastHandledOutboundStream, $message")
        // TODO: should we consider the connection borked?
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
