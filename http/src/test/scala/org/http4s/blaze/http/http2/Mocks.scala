package org.http4s.blaze.http.http2

import org.http4s.blaze.util.Execution

import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext

private class MockFlowControl(
  mySettings: Http2Settings,
  peerSettings: Http2Settings
) extends SessionFlowControlImpl(mySettings, peerSettings) {
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


private class Http2MockTools(isClient: Boolean) {

  lazy val mySettings: MutableHttp2Settings = MutableHttp2Settings.default()

  lazy val peerSettings: MutableHttp2Settings = MutableHttp2Settings.default()

  lazy val flowControl: MockFlowControl = new MockFlowControl(mySettings, peerSettings)

  lazy val sessionExecutor: ExecutionContext = Execution.trampoline
}
