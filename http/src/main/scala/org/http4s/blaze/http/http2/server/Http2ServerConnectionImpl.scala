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
    inboundStreamBuilder: Int => Option[LeafBuilder[StreamMessage]]): Http2ServerConnectionImpl = {
    val flowStrategy = new DefaultFlowStrategy(mySettings)
    val ec = Execution.trampoline

    new Http2ServerConnectionImpl(
      tailStage,
      inboundStreamBuilder,
      mySettings,
      peerSettings,
      flowStrategy,
      ec
    )
  }
}

private final class Http2ServerConnectionImpl(
    tailStage: BasicTail[ByteBuffer],
    inboundStreamBuilder: Int => Option[LeafBuilder[StreamMessage]],
    localSettings: ImmutableHttp2Settings, // the settings of this side
    remoteSettings: MutableHttp2Settings,
    flowStrategy: FlowStrategy,
    parentExecutor: ExecutionContext)
  extends Http2ConnectionImpl(
    isClient = false,
    tailStage = tailStage,
    localSettings = localSettings,
    remoteSettings = remoteSettings,  // peer settings
    flowStrategy = flowStrategy,
    inboundStreamBuilder = inboundStreamBuilder,
    parentExecutor = parentExecutor) {

  // Boom. It begins.
  tailStage.started.foreach(_ => startSession())(Execution.directec)
}
