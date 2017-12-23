package org.http4s.blaze.http.http2.mocks

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2._

import scala.concurrent.Future

private[http2] abstract class MockStreamState extends StreamState {
  override def closeWithError(t: Option[Throwable]): Unit = ???
  var calledOutboundFlowWindowChanged = false
  override def outboundFlowWindowChanged(): Unit = {
    calledOutboundFlowWindowChanged = true
  }
  override def performStreamWrite(): Seq[ByteBuffer] = ???
  override def invokeInboundData(endStream: Boolean, data: ByteBuffer, flowBytes: Int): MaybeError = ???
  override def invokeInboundHeaders(priority: Priority, endStream: Boolean, headers: Seq[(String, String)]): MaybeError = ???
  override def flowWindow: StreamFlowWindow = ???
  override def writeRequest(data: StreamMessage): Future[Unit] = ???
  override def readRequest(size: Int): Future[StreamMessage] = ???
  override def name: String = s"MockStreamState($streamId)"
}
