package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.Headers
import org.http4s.blaze.http.http2.mocks.MockStreamManager.FinishedStream
import org.http4s.blaze.http.http2.{Http2Result, InboundStreamState, MaybeError, _}

import scala.collection.mutable
import scala.concurrent.Future

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

  override def idManager: StreamIdManager = ???

  override def initialFlowWindowChange(delta: Int): MaybeError = ???

  override def flowWindowUpdate(streamId: Int, sizeIncrement: Int): MaybeError = ???

  override def registerInboundStream(state: InboundStreamState): Boolean = ???

  override def handlePushPromise(streamId: Int, promisedId: Int, headers: Headers): Http2Result = ???

  override def get(streamId: Int): Option[StreamState] = ???

  override def goaway(lastHandledOutboundStream: Int, message: String): Future[Unit] = ???


  override def closeStream(id: Int, cause: Option[Throwable]): Boolean = ???

  override def close(cause: Option[Throwable]): Unit = ???

  override def isEmpty: Boolean = ???
}

private[http2] object MockStreamManager {
  case class FinishedStream(stream: StreamState, cause: Option[Http2Exception])
}
