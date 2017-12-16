package org.http4s.blaze.http.http2

import org.http4s.blaze.http.http2.MockFlowControl.{Operation, SessionConsumed, StreamConsumed}

import scala.collection.mutable.ListBuffer

private object MockFlowControl {
  sealed trait Operation
  case class SessionConsumed(bytes: Int) extends Operation
  case class StreamConsumed(stream: StreamFlowWindow, consumed: Int) extends Operation
}

private class MockFlowControl(
    session: SessionCore
  ) extends SessionFlowControlImpl(session, null /* only used on two overridden methods */) {

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