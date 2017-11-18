package org.http4s.blaze.http.http2.mocks

import org.http4s.blaze.http.http2.{WriteInterest, _}

import scala.collection.mutable.ListBuffer

private class MockFlowControl(
                               flowStrategy: FlowStrategy,
                               mySettings: Http2Settings,
                               peerSettings: Http2Settings
                             ) extends SessionFlowControlImpl(
  flowStrategy, MockFlowControl.synthCore(mySettings, peerSettings)) {
  sealed trait Operation
  case class SessionConsumed(bytes: Int) extends Operation
  case class StreamConsumed(stream: StreamFlowWindow, consumed: Int) extends Operation

  val observedOps = new ListBuffer[Operation]

  override protected def onSessonBytesConsumed(consumed: Int): Unit = {
    observedOps += SessionConsumed(consumed)
    ()
  }

  override protected def onStreamBytesConsumed(stream: StreamFlowWindow, consumed: Int): Unit = {
    observedOps += StreamConsumed(stream, consumed)
    ()
  }
}

private class MockWriteListener extends WriteListener {
  val observedInterests = new ListBuffer[WriteInterest]
  override def registerWriteInterest(interest: WriteInterest): Unit = {
    observedInterests += interest
    ()
  }
}

private object MockFlowControl {
  private def synthCore(mySettings: Http2Settings,
                        peerSettings: Http2Settings): SessionCore = ???
}
