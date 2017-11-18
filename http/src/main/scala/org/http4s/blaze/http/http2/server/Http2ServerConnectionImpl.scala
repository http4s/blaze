package org.http4s.blaze.http.http2.server

import java.nio.ByteBuffer

import org.http4s.blaze.http.http2._
import org.http4s.blaze.pipeline.stages.BasicTail
import org.http4s.blaze.pipeline.LeafBuilder
import org.http4s.blaze.util.Execution

import scala.concurrent.ExecutionContext

private object Http2ServerConnectionImpl {

  /** Construct a [[Http2ServerConnectionImpl]] with default settings */
  def default(
    tailStage: BasicTail[ByteBuffer],
    mySettings: ImmutableHttp2Settings,
    peerSettings: MutableHttp2Settings,
    nodeBuilder: Int => LeafBuilder[StreamMessage]): Http2ServerConnectionImpl = {
    val flowStrategy = new DefaultFlowStrategy(mySettings)
    val ec = Execution.trampoline

    new Http2ServerConnectionImpl(
      tailStage,
      nodeBuilder,
      mySettings,
      peerSettings,
      flowStrategy,
      ec
    )
  }
}

private final class Http2ServerConnectionImpl(
    tailStage: BasicTail[ByteBuffer],
    nodeBuilder: Int => LeafBuilder[StreamMessage],
    mySettings: ImmutableHttp2Settings, // the settings of this side
    peerSettings: MutableHttp2Settings,
    flowStrategy: FlowStrategy,
    executor: ExecutionContext)
  extends Http2ConnectionImpl(
    tailStage,
    mySettings,
    peerSettings,  // peer settings
    flowStrategy: FlowStrategy,
    executor: ExecutionContext) {

  // Boom. It begins.
  tailStage.started.foreach(_ => startSession())(Execution.directec)

  override protected def newStreamManager(session: SessionCore): StreamManager = ???
}
