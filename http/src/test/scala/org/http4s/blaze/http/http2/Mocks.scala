package org.http4s.blaze.http.http2

import org.http4s.blaze.util.{Execution, SerialExecutionContext}
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

private class MockWriteListener extends WriteListener {
  val observedInterests = new ListBuffer[WriteInterest]
  override def registerWriteInterest(interest: WriteInterest): Unit = {
    observedInterests += interest
    ()
  }
}

private class MockHttp2StreamState(
    val streamId: Int, tools: Http2MockTools)
  extends Http2StreamState(tools.writeListener, tools.frameEncoder, tools.sessionExecutor) {

  var outboundFlowAcks: Int = 0

  var onStreamFinishedResult: Option[Option[Http2Exception]] = None

  override val flowWindow: StreamFlowWindow = tools.flowControl.newStreamFlowWindow(streamId)

  override def outboundFlowWindowChanged(): Unit = {
    outboundFlowAcks += 1
    super.outboundFlowWindowChanged()
  }

  /** Deals with stream related errors */
  override protected def onStreamFinished(ex: Option[Http2Exception]): Unit = {
    onStreamFinishedResult = Some(ex)
  }

  override protected def maxFrameSize: Int = tools.mySettings.maxFrameSize
}

private class Http2MockTools(isClient: Boolean) {

  lazy val mySettings: MutableHttp2Settings = MutableHttp2Settings.default()

  lazy val peerSettings: MutableHttp2Settings = MutableHttp2Settings.default()

  lazy val flowControl: MockFlowControl = new MockFlowControl(mySettings, peerSettings)

  lazy val sessionExecutor: ExecutionContext = Execution.trampoline

  lazy val headerEncoder: HeaderEncoder = new HeaderEncoder(peerSettings.headerTableSize)

  lazy val headerDecoder: HeaderDecoder = new HeaderDecoder(mySettings.maxHeaderListSize, mySettings.headerTableSize)

  lazy val frameEncoder: Http2FrameEncoder = new Http2FrameEncoder(peerSettings, headerEncoder)

  lazy val writeListener: MockWriteListener = new MockWriteListener

  lazy val idManager: StreamIdManager = StreamIdManager(isClient)

  def newStream(id: Int): MockHttp2StreamState = new MockHttp2StreamState(id, this)
}
