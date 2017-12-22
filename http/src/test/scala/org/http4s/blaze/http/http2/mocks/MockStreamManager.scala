package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.Headers
import org.http4s.blaze.http.http2.{Http2Result, InboundStreamState, MaybeError, _}

import scala.collection.mutable
import scala.concurrent.Future

private[http2] class MockStreamManager extends StreamManager {
  override def size: Int = 0

  val finishedStreams = new mutable.Queue[StreamState]

  val registeredOutboundStreams = new mutable.Queue[OutboundStreamState]

  override def streamClosed(stream: StreamState): Boolean = {
    finishedStreams += stream
    true
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

  override def goAway(lastHandledOutboundStream: Int, message: String): Future[Unit] = ???

  override def rstStream(cause: Http2StreamException): Boolean = ???

  override def forceClose(cause: Option[Throwable]): Unit = ???

  override def isEmpty: Boolean = ???
}

