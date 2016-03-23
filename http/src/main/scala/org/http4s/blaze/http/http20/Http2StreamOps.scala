package org.http4s.blaze.http.http20

import java.nio.ByteBuffer
import org.http4s.blaze.http.http20.NodeMsg.Http2Msg
import org.http4s.blaze.pipeline.Command.OutboundCommand
import scala.concurrent.Future

/** Operations necessary for the [[Http2Stage]] */
private trait Http2StreamOps {

  /** Write the buffers to the socket */
  def writeBuffers(data: Seq[ByteBuffer]): Future[Unit]

  /** Manage a stream read request */
  def streamRead(stream: Http2Stream): Future[Http2Msg]

  /** Manage a stream write request */
  def streamWrite(stream: Http2Stream, data: Seq[Http2Msg]): Future[Unit]

  /** Manage a stream pipeline command */
  def streamCommand(stream: Http2Stream, cmd: OutboundCommand): Unit

  /** Manage stream failure */
  def onFailure(t: Throwable, position: String): Unit
}
