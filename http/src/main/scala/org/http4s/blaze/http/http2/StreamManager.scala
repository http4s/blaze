package org.http4s.blaze.http.http2

import scala.collection.mutable.HashMap

class StreamManager[+StreamState <: Http2StreamState](
                                                    sessionFlowControl: SessionFlowControl
                                                    ) {

  private[this] val logger = org.log4s.getLogger
  private[this] val streams = new HashMap[Int, StreamState]

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

  def get(id: Int): Option[StreamState] =
    streams.get(id)

  def close(cause: Option[Throwable]): Unit = {
    val ss = streams.values.toVector
    for (stream <- ss) {
      streams.remove(stream.streamId)
      stream.closeWithError(cause)
    }
  }

  /** Close the specified stream
    *
    * @param id stream-id
    * @param cause reason for closing the stream
    * @return true if the stream existed and was closed, false otherwise
    */
  def closeStream(id: Int, cause: Option[Throwable]): Boolean = {
    ???
  }

  def windowUpdate(streamId: Int, sizeIncrement: Int): MaybeError = {
    if (streamId == 0) {
      val result = sessionFlowControl.sessionOutboundAcked(sizeIncrement)
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
}
