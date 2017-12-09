package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.http2.mocks.MockStreamManager.FinishedStream
import org.http4s.blaze.http.http2.{Http2Exception, OutboundStreamState, StreamManager, StreamState}

import scala.collection.mutable

private[http2] class MockStreamManager extends StreamManager {
  override def size: Int = 0

  val finishedStreams = new mutable.Queue[FinishedStream]

  val registeredOutboundStreams = new mutable.Queue[OutboundStreamState]

  override def streamFinished(stream: StreamState, cause: Option[Http2Exception]): Unit = {
    finishedStreams += FinishedStream(stream, cause)
    ()
  }


  override def registerOutboundStream(state: OutboundStreamState): Option[Int] = {
    registeredOutboundStreams += state
    Some(1)
  }
}

private[http2] object MockStreamManager {
  case class FinishedStream(stream: StreamState, cause: Option[Http2Exception])
}
